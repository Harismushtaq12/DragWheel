using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using Microsoft.Win32;

namespace DragWheel
{
    internal static class Settings
    {
        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string AppName = "DragWheel";
        private static readonly string[] MenuKeys =
        {
            @"Software\Classes\*\shell\DragWheel",
            @"Software\Classes\Directory\shell\DragWheel",
        };

        public static string DataFolder
        {
            get
            {
                var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), AppName);
                Directory.CreateDirectory(dir);
                return dir;
            }
        }

        private static string Exe => Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "DragWheel.exe");

        /// <summary>True the first time the app ever runs on this PC.</summary>
        public static bool IsFirstRun()
        {
            var marker = Path.Combine(DataFolder, "first-run-done");
            if (File.Exists(marker)) return false;
            try { File.WriteAllText(marker, DateTime.Now.ToString("O")); } catch { }
            return true;
        }

        // ---------- Stored choices ----------

        private sealed class Store
        {
            public bool WheelOnShiftDrag { get; set; } = true;
        }

        private static string StorePath => Path.Combine(DataFolder, "settings.json");
        private static Store? _store;

        private static Store Load()
        {
            if (_store != null) return _store;
            try { _store = JsonSerializer.Deserialize<Store>(File.ReadAllText(StorePath)); } catch { }
            return _store ??= new Store();
        }

        private static void SaveStore()
        {
            try { File.WriteAllText(StorePath, JsonSerializer.Serialize(Load())); } catch { }
        }

        public static bool WheelOnShiftDrag
        {
            get => Load().WheelOnShiftDrag;
            set { Load().WheelOnShiftDrag = value; SaveStore(); }
        }

        // ---------- Windows integration ----------

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
                if (value) key.SetValue(AppName, $"\"{Exe}\" --background");
                else key.DeleteValue(AppName, throwOnMissingValue: false);
            }
        }

        /// <summary>"Convert with DragWheel" when you right-click a file or folder in File Explorer.</summary>
        public static bool ExplorerMenu
        {
            get
            {
                using var key = Registry.CurrentUser.OpenSubKey(MenuKeys[0]);
                return key != null;
            }
            set
            {
                foreach (var path in MenuKeys)
                {
                    if (value)
                    {
                        using var key = Registry.CurrentUser.CreateSubKey(path);
                        key.SetValue("", "Convert with DragWheel");
                        key.SetValue("Icon", $"\"{Exe}\",0");
                        // Lets Explorer hand over many selected files at once.
                        key.SetValue("MultiSelectModel", "Player");
                        using var command = key.CreateSubKey("command");
                        command.SetValue("", $"\"{Exe}\" \"%1\"");
                    }
                    else
                    {
                        Registry.CurrentUser.DeleteSubKeyTree(path, throwOnMissingSubKey: false);
                    }
                }
            }
        }

        private static string SendToShortcut =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "DragWheel.lnk");

        /// <summary>DragWheel in the right-click "Send to" menu (sends all selected files at once).</summary>
        public static bool SendTo
        {
            get => File.Exists(SendToShortcut);
            set
            {
                if (!value)
                {
                    try { File.Delete(SendToShortcut); } catch { }
                    return;
                }
                var type = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("Windows Script Host is not available.");
                dynamic shell = Activator.CreateInstance(type)!;
                try
                {
                    dynamic link = shell.CreateShortcut(SendToShortcut);
                    link.TargetPath = Exe;
                    link.IconLocation = Exe + ",0";
                    link.Description = "Convert with DragWheel";
                    link.Save();
                }
                finally
                {
                    System.Runtime.InteropServices.Marshal.FinalReleaseComObject(shell);
                }
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

    /// <summary>One finished job, for the Recent list.</summary>
    public sealed class HistoryItem
    {
        public DateTime Time { get; set; }
        public string Title { get; set; } = "";
        public List<string> Sources { get; set; } = new();
        public List<string> Outputs { get; set; } = new();

        public string FirstOutputName => Outputs.Count == 0 ? "" : Path.GetFileName(Outputs[0].TrimEnd('\\'))
            + (Outputs.Count > 1 ? $" and {Outputs.Count - 1} more" : "");
        public string FromLine => Sources.Count == 1 ? "from " + Path.GetFileName(Sources[0].TrimEnd('\\')) : $"from {Sources.Count} files";
        public string When => Time.Date == DateTime.Today ? Time.ToString("HH:mm") : Time.ToString("d MMM, HH:mm");
        public bool Exists => Outputs.Any(o => File.Exists(o) || Directory.Exists(o));
    }

    internal static class History
    {
        private static string PathOnDisk => Path.Combine(Settings.DataFolder, "history.json");
        public static event Action? Changed;

        public static List<HistoryItem> Load()
        {
            try { return JsonSerializer.Deserialize<List<HistoryItem>>(File.ReadAllText(PathOnDisk)) ?? new(); }
            catch { return new(); }
        }

        public static void Add(HistoryItem item)
        {
            var list = Load();
            list.Insert(0, item);
            if (list.Count > 100) list.RemoveRange(100, list.Count - 100);
            Save(list);
        }

        public static void Save(List<HistoryItem> list)
        {
            try { File.WriteAllText(PathOnDisk, JsonSerializer.Serialize(list)); } catch { }
            Changed?.Invoke();
        }
    }
}
