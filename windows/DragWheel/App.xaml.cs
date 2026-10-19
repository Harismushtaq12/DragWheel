using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using DragWheel.Engines;
using PdfSharp.Fonts;

namespace DragWheel
{
    public partial class App : Application
    {
        private Mutex? _singleInstance;
        private MouseHook? _hook;
        private WheelWindow? _wheel;
        private TrayIcon? _tray;
        private MainWindow? _main;

        // Files from Explorer can arrive one by one; gather them for a moment, then show one wheel.
        private readonly List<string> _incoming = new();
        private DispatcherTimer? _incomingTimer;

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            var args = e.Args.ToList();

            _singleInstance = new Mutex(true, @"Local\DragWheel.SingleInstance", out bool isFirst);
            if (!isFirst)
            {
                // Hand the files (or "open the window") to the copy that is already running.
                if (!SingleInstance.TrySend(args.Count > 0 ? args : new List<string> { "--open" }))
                {
                    MessageBox.Show("DragWheel is already running. Look for its icon near the clock.",
                        "DragWheel", MessageBoxButton.OK, MessageBoxImage.Information);
                }
                Shutdown();
                return;
            }

            DispatcherUnhandledException += OnUnhandledException;
            // PDFsharp draws page numbers and watermarks with the fonts installed in Windows.
            GlobalFontSettings.UseWindowsFontsUnderWindows = true;

            _tray = new TrayIcon(OpenMainWindow, ChooseFiles);
            _wheel = new WheelWindow();
            _wheel.ActionPicked += (action, files) => _ = RunActionAsync(action, files);

            _hook = new MouseHook(Dispatcher);
            _hook.ShiftDragStarted += (x, y) => { if (Settings.WheelOnShiftDrag) _wheel.Arm(x, y); };
            _hook.LeftButtonReleased += () => _wheel.MouseReleased();
            _hook.LeftButtonPressed += (x, y) => _wheel.ScreenClicked(x, y);

            try
            {
                _hook.Start();
            }
            catch (Exception ex)
            {
                MessageBox.Show("DragWheel could not watch the mouse:\n" + ex.Message,
                    "DragWheel", MessageBoxButton.OK, MessageBoxImage.Error);
                Shutdown();
                return;
            }

            _incomingTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(350) };
            _incomingTimer.Tick += (_, _) =>
            {
                _incomingTimer.Stop();
                var files = _incoming.Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
                _incoming.Clear();
                ShowWheelForFiles(files);
            };
            SingleInstance.Listen(lines => Dispatcher.InvokeAsync(() => Handle(lines)));

            bool firstRun = Settings.IsFirstRun();
            if (firstRun)
            {
                // Sensible defaults: the right-click menu and Send to.
                try { Settings.ExplorerMenu = true; } catch (Exception ex) { Log.Write(ex.Message); }
                try { Settings.SendTo = true; } catch (Exception ex) { Log.Write(ex.Message); }
            }

            if (args.Count > 0 && args[0] != "--background") Handle(args);
            else if (firstRun || args.Count == 0) OpenMainWindow();
        }

        private void Handle(List<string> lines)
        {
            var files = lines.Where(l => !l.StartsWith("--") && (File.Exists(l) || Directory.Exists(l))).ToList();
            if (files.Count == 0)
            {
                if (lines.Contains("--open") || lines.Count == 0) OpenMainWindow();
                return;
            }
            _incoming.AddRange(files);
            _incomingTimer?.Stop();
            _incomingTimer?.Start();
        }

        /// <summary>Opens the wheel under the mouse pointer for these files.</summary>
        public void ShowWheelForFiles(string[] files)
        {
            if (files.Length == 0 || _wheel == null) return;
            NativeMethods.GetCursorPos(out var p);
            _wheel.ShowFor(files, p.X, p.Y);
        }

        private void ChooseFiles()
        {
            var dialog = new Microsoft.Win32.OpenFileDialog { Multiselect = true, Title = "Choose files to convert" };
            if (dialog.ShowDialog() == true) ShowWheelForFiles(dialog.FileNames);
        }

        public void OpenMainWindow()
        {
            if (_main == null)
            {
                _main = new MainWindow(ShowWheelForFiles, ChooseFiles);
                _main.Closed += (_, _) => _main = null;
            }
            _main.Show();
            if (_main.WindowState == WindowState.Minimized) _main.WindowState = WindowState.Normal;
            _main.Activate();
        }

        protected override void OnExit(ExitEventArgs e)
        {
            _hook?.Dispose();
            _tray?.Dispose();
            _singleInstance?.Dispose();
            base.OnExit(e);
        }

        private async Task RunActionAsync(WheelAction action, string[] files)
        {
            if (_tray == null) return;

            if (action.Engine == Engine.Ffmpeg && Ffmpeg.Find() == null) Ffmpeg.Refresh();
            if (action.Engine == Engine.Ffmpeg && Ffmpeg.Find() == null)
            {
                _tray.Notify("ffmpeg is needed",
                    "Audio and video need ffmpeg. Right-click the DragWheel icon and choose Install ffmpeg.", isError: true);
                return;
            }
            if (action.Engine == Engine.Office)
            {
                LibreOffice.Refresh();
                var family = Tools.OfficeOps.FamilyOf(files[0]);
                if (family != null && !Tools.OfficeOps.CanConvert(family.Value))
                {
                    _tray.Notify("Office or LibreOffice is needed",
                        "Install the free LibreOffice: right-click the DragWheel icon and choose Install LibreOffice.", isError: true);
                    return;
                }
            }

            string? answer = null;
            if (action.Ask != null)
            {
                answer = InputDialog.Ask(action.Title, action.Ask.Question, action.Ask.Default, action.Ask.Password);
                if (answer == null) return; // cancelled
            }

            // Only show "working" for jobs that take a while.
            var job = Task.Run(() => action.Run(new Job(files, answer)));
            var finishedQuickly = await Task.WhenAny(job, Task.Delay(1200)) == job;
            if (!finishedQuickly)
                _tray.Notify(action.Title, files.Length == 1 ? $"Working on {Path.GetFileName(files[0])}…" : $"Working on {files.Length} files…");

            ActionResult result;
            try
            {
                result = await job;
            }
            catch (Exception ex)
            {
                Log.Write($"{action.Title}: {ex}");
                _tray.Notify($"{action.Title} failed", Clean(ex.Message), isError: true);
                return;
            }

            if (result.ClipboardText != null)
            {
                try { Clipboard.SetText(result.ClipboardText); } catch { /* clipboard busy */ }
            }

            if (result.Files.Count > 0)
            {
                History.Add(new HistoryItem { Time = DateTime.Now, Title = action.Done, Sources = files.ToList(), Outputs = result.Files.ToList() });
                OpenInExplorer(result.Files[0]);
                var name = Path.GetFileName(result.Files[0].TrimEnd('\\'));
                var more = result.Files.Count > 1 ? $" and {result.Files.Count - 1} more" : "";
                if (result.Failed.Count > 0)
                {
                    foreach (var f in result.Failed) Log.Write($"{action.Title}: {f}");
                    _tray.Notify($"{result.Files.Count} done, {result.Failed.Count} failed",
                        Clean(string.Join("\n", result.Failed.Take(2))), isError: true);
                }
                else _tray.Notify(action.Done, name + more);
            }
            else if (result.Message != null)
            {
                _tray.Notify(action.Done, result.Message);
            }
        }

        private static string Clean(string message) =>
            message.Length > 240 ? message.Substring(0, 240) + "…" : message;

        private static void OpenInExplorer(string path)
        {
            try
            {
                Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"") { UseShellExecute = true });
            }
            catch { /* not critical */ }
        }

        private void OnUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
        {
            Log.Write(e.Exception.ToString());
            _tray?.Notify("Something went wrong", e.Exception.Message, isError: true);
            e.Handled = true;
        }
    }
}
