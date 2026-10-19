using System;
using System.IO;
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

        private static async Task OnSta(Action work)
        {
            await Gate.WaitAsync();
            try
            {
                var done = new TaskCompletionSource();
                var t = new Thread(() =>
                {
                    try { work(); done.SetResult(); }
                    catch (Exception e) { done.SetException(e.InnerException ?? e); }
                });
                t.SetApartmentState(ApartmentState.STA);
                t.IsBackground = true;
                t.Start();
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
            try
            {
                app = Activator.CreateInstance(Type.GetTypeFromProgID("Word.Application")!)!;
                app.Visible = false;
                app.DisplayAlerts = 0;
                // FileName, ConfirmConversions, ReadOnly, AddToRecentFiles
                doc = app.Documents.Open(Path.GetFullPath(input), false, true, false);
                if (format == 17) doc.ExportAsFixedFormat(Path.GetFullPath(output), 17);
                else doc.SaveAs2(Path.GetFullPath(output), format);
            }
            finally
            {
                try { doc?.Close(false); } catch { }
                try { app?.Quit(false); } catch { }
                Release(doc);
                Release(app);
            }
        });

        /// <summary>Excel: -1 = PDF (export); otherwise an Excel SaveAs format number.</summary>
        public static Task Excel(string input, string output, int format) => OnSta(() =>
        {
            dynamic? app = null;
            dynamic? book = null;
            try
            {
                app = Activator.CreateInstance(Type.GetTypeFromProgID("Excel.Application")!)!;
                app.Visible = false;
                app.DisplayAlerts = false;
                // FileName, UpdateLinks, ReadOnly
                book = app.Workbooks.Open(Path.GetFullPath(input), 0, true);
                if (format == -1) book.ExportAsFixedFormat(0, Path.GetFullPath(output));
                else book.SaveAs(Path.GetFullPath(output), format);
            }
            finally
            {
                try { book?.Close(false); } catch { }
                try { app?.Quit(); } catch { }
                Release(book);
                Release(app);
            }
        });

        /// <summary>PowerPoint: a SaveAs format number (32 = PDF, 24 = PPTX, 35 = ODP).</summary>
        public static Task PowerPoint(string input, string output, int format) => OnSta(() =>
        {
            dynamic? app = null;
            dynamic? pres = null;
            try
            {
                app = Activator.CreateInstance(Type.GetTypeFromProgID("PowerPoint.Application")!)!;
                // FileName, ReadOnly (msoTrue = -1), Untitled, WithWindow (msoFalse = 0)
                pres = app.Presentations.Open(Path.GetFullPath(input), -1, 0, 0);
                pres.SaveAs(Path.GetFullPath(output), format);
            }
            finally
            {
                try { pres?.Close(); } catch { }
                try { app?.Quit(); } catch { }
                Release(pres);
                Release(app);
            }
        });
    }
}
