using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;

namespace DragWheel.Engines
{
    /// <summary>
    /// Uses Microsoft Word, Excel or PowerPoint when they are installed. They give the most exact results
    /// for Office files. Each job runs on its own STA thread, and the program is always closed afterwards.
    /// </summary>
    internal static class Office
    {
        private static bool Has(string progId)
        {
            try { return Type.GetTypeFromProgID(progId) != null; }
            catch { return false; }
        }

        public static bool HasWord => Has("Word.Application");
        public static bool HasExcel => Has("Excel.Application");
        public static bool HasPowerPoint => Has("PowerPoint.Application");

        // Only one Office job at a time: Office is not built for many at once.
        private static readonly SemaphoreSlim Gate = new(1, 1);

        private static readonly string[] OfficeProcesses = { "WINWORD", "EXCEL", "POWERPNT" };

        private static HashSet<int> RunningOfficeIds() =>
            OfficeProcesses.SelectMany(n => Process.GetProcessesByName(n)).Select(p => p.Id).ToHashSet();

        private static async Task OnSta(Action work)
        {
            await Gate.WaitAsync();
            var before = RunningOfficeIds();
            try
            {
                var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                var t = new Thread(() =>
                {
                    try { work(); done.TrySetResult(); }
                    catch (Exception e) { done.TrySetException(e.InnerException ?? e); }
                    finally
                    {
                        // Free Office's hidden objects now, so WINWORD/EXCEL do not stay running.
                        GC.Collect();
                        GC.WaitForPendingFinalizers();
                        GC.Collect();
                        GC.WaitForPendingFinalizers();
                    }
                });
                t.SetApartmentState(ApartmentState.STA);
                t.IsBackground = true;
                t.Start();
                if (await Task.WhenAny(done.Task, Task.Delay(TimeSpan.FromMinutes(3))) != done.Task)
                {
                    // Office is stuck (usually a hidden question). Stop only the copies we started.
                    foreach (var p in OfficeProcesses.SelectMany(n => Process.GetProcessesByName(n)))
                        if (!before.Contains(p.Id)) try { p.Kill(); } catch { }
                    throw new TimeoutException("Office did not answer, so the conversion was stopped. The file may be damaged or need a password.");
                }
                await done.Task;
            }
            finally
            {
                Gate.Release();
            }
        }

        private static void Release(object? o)
        {
            if (o != null && Marshal.IsComObject(o))
            {
                try { Marshal.FinalReleaseComObject(o); } catch { }
            }
        }

        /// <summary>Word: 17 = PDF (export); otherwise a Word SaveAs format number.</summary>
        public static Task Word(string input, string output, int format) => OnSta(() =>
        {
            dynamic? app = null;
            dynamic? doc = null;
            int openBefore = 0;
            try
            {
                app = Activator.CreateInstance(Type.GetTypeFromProgID("Word.Application")!)!;
                try { openBefore = app.Documents.Count; } catch { }
                app.Visible = false;
                app.DisplayAlerts = 0;
                // A dummy password makes protected files fail at once instead of waiting for a hidden prompt.
                doc = app.Documents.Open(FileName: Path.GetFullPath(input), ConfirmConversions: false, ReadOnly: true,
                    AddToRecentFiles: false, PasswordDocument: "\u0001", Visible: false);
                if (format == 17) doc.ExportAsFixedFormat(Path.GetFullPath(output), 17);
                else if (format == 2) doc.SaveAs2(FileName: Path.GetFullPath(output), FileFormat: 2, Encoding: 65001); // UTF-8 text
                else doc.SaveAs2(Path.GetFullPath(output), format);
            }
            finally
            {
                try { doc?.Close(false); } catch { }
                // Only close Word if it was not already open with the user's documents.
                if (openBefore == 0) try { app?.Quit(false); } catch { }
                Release(doc);
                Release(app);
            }
        });

        /// <summary>Excel: -1 = PDF (export); otherwise an Excel SaveAs format number.</summary>
        public static Task Excel(string input, string output, int format) => OnSta(() =>
        {
            dynamic? app = null;
            dynamic? book = null;
            int openBefore = 0;
            try
            {
                app = Activator.CreateInstance(Type.GetTypeFromProgID("Excel.Application")!)!;
                try { openBefore = app.Workbooks.Count; } catch { }
                app.DisplayAlerts = false;
                book = app.Workbooks.Open(Filename: Path.GetFullPath(input), UpdateLinks: 0, ReadOnly: true, Password: "\u0001");
                if (format == -1) book.ExportAsFixedFormat(0, Path.GetFullPath(output));
                else if (format == 62)
                {
                    // CSV in UTF-8 (Excel 2016+), so all languages survive; older Excel falls back to plain CSV.
                    try { book.SaveAs(Path.GetFullPath(output), 62); }
                    catch { book.SaveAs(Path.GetFullPath(output), 6); }
                }
                else book.SaveAs(Path.GetFullPath(output), format);
            }
            finally
            {
                try { book?.Close(false); } catch { }
                if (openBefore == 0) try { app?.Quit(); } catch { }
                Release(book);
                Release(app);
            }
        });

        /// <summary>PowerPoint: a SaveAs format number (32 = PDF, 24 = PPTX, 35 = ODP).</summary>
        public static Task PowerPoint(string input, string output, int format) => OnSta(() =>
        {
            dynamic? app = null;
            dynamic? pres = null;
            int openBefore = 0;
            try
            {
                // PowerPoint is shared: this may be the user's own open PowerPoint.
                app = Activator.CreateInstance(Type.GetTypeFromProgID("PowerPoint.Application")!)!;
                try { openBefore = app.Presentations.Count; } catch { }
                // FileName, ReadOnly (msoTrue = -1), Untitled, WithWindow (msoFalse = 0)
                pres = app.Presentations.Open(Path.GetFullPath(input), -1, 0, 0);
                pres.SaveAs(Path.GetFullPath(output), format);
            }
            finally
            {
                try { pres?.Close(); } catch { }
                if (openBefore == 0) try { app?.Quit(); } catch { }
                Release(pres);
                Release(app);
            }
        });
    }
}
