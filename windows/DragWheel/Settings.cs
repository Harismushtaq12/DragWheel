using System;
using System.IO;
using Microsoft.Win32;

namespace DragWheel
{
    internal static class Settings
    {
        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string AppName = "DragWheel";

        public static string DataFolder
        {
            get
            {
                var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), AppName);
                Directory.CreateDirectory(dir);
                return dir;
            }
        }

        /// <summary>True the first time the app ever runs on this PC.</summary>
        public static bool IsFirstRun()
        {
            var marker = Path.Combine(DataFolder, "first-run-done");
            if (File.Exists(marker)) return false;
            try { File.WriteAllText(marker, DateTime.Now.ToString("O")); } catch { }
            return true;
        }

        public static bool StartsWithWindows
        {
            get
            {
                using var key = Registry.CurrentUser.OpenSubKey(RunKey);
                return key?.GetValue(AppName) != null;
            }
            set
            {
                using var key = Registry.CurrentUser.CreateSubKey(RunKey);
                if (value && Environment.ProcessPath != null)
                    key.SetValue(AppName, $"\"{Environment.ProcessPath}\"");
                else
                    key.DeleteValue(AppName, throwOnMissingValue: false);
            }
        }
    }

    internal static class Log
    {
        public static string FilePath => Path.Combine(Settings.DataFolder, "errors.log");

        public static void Write(string text)
        {
            try { File.AppendAllText(FilePath, $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {text}{Environment.NewLine}"); }
            catch { /* logging must never crash the app */ }
        }
    }
}
