using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;

namespace DragWheel
{
    public partial class App : Application
    {
        private Mutex? _singleInstance;
        private MouseHook? _hook;
        private WheelWindow? _wheel;
        private TrayIcon? _tray;

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);

            _singleInstance = new Mutex(true, @"Local\DragWheel.SingleInstance", out bool isFirst);
            if (!isFirst)
            {
                MessageBox.Show("DragWheel is already running. Look for its icon near the clock.",
                    "DragWheel", MessageBoxButton.OK, MessageBoxImage.Information);
                Shutdown();
                return;
            }

            DispatcherUnhandledException += OnUnhandledException;

            _tray = new TrayIcon();
            _wheel = new WheelWindow();
            _wheel.ActionPicked += (action, files) => _ = RunActionAsync(action, files);

            _hook = new MouseHook(Dispatcher);
            _hook.ShiftDragStarted += (x, y) => _wheel.Arm(x, y);
            _hook.LeftButtonReleased += () => _wheel.MouseReleased();

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

            if (Settings.IsFirstRun())
            {
                _tray.Notify("DragWheel is running",
                    "Hold Shift while you drag a file. Drop it on the wheel to convert it.");
            }
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

            if (action.NeedsFfmpeg && Ffmpeg.Find() == null)
            {
                _tray.Notify("ffmpeg is needed",
                    "Audio and video need ffmpeg. Right-click the DragWheel icon and choose Install ffmpeg.",
                    isError: true);
                return;
            }

            // Only show "working" for jobs that take a while.
            var job = Task.Run(() => action.Run(files));
            var finishedQuickly = await Task.WhenAny(job, Task.Delay(1200)) == job;
            if (!finishedQuickly)
                _tray.Notify(action.Title, $"Working on {Describe(files)}…");

            ActionResult result;
            try
            {
                result = await job;
            }
            catch (Exception ex)
            {
                _tray.Notify($"{action.Title} failed", Clean(ex.Message), isError: true);
                return;
            }

            if (result.ClipboardText != null)
            {
                try { Clipboard.SetText(result.ClipboardText); } catch { /* clipboard busy */ }
            }

            if (result.Files.Count > 0)
            {
                OpenInExplorer(result.Files[0]);
                var name = Path.GetFileName(result.Files[0].TrimEnd('\\'));
                var more = result.Files.Count > 1 ? $" and {result.Files.Count - 1} more" : "";
                _tray.Notify(action.Done, name + more);
            }
            else if (result.Message != null)
            {
                _tray.Notify(action.Done, result.Message);
            }
        }

        private static string Describe(string[] files) =>
            files.Length == 1 ? Path.GetFileName(files[0]) : $"{files.Length} files";

        private static string Clean(string message) =>
            message.Length > 240 ? message.Substring(message.Length - 240) : message;

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
