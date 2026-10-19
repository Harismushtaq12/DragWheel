using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using DragWheel.Engines;

namespace DragWheel
{
    /// <summary>The app window: drop zone, recent conversions, and settings.</summary>
    public partial class MainWindow : Window
    {
        private readonly Action<string[]> _showWheel;
        private readonly Action _chooseFiles;
        private static readonly Brush Idle = new SolidColorBrush(Color.FromRgb(0x2C, 0x3A, 0x49));
        private static readonly Brush Hot = new SolidColorBrush(Color.FromRgb(0x7F, 0xE0, 0xC2));

        public MainWindow(Action<string[]> showWheel, Action chooseFiles)
        {
            InitializeComponent();
            _showWheel = showWheel;
            _chooseFiles = chooseFiles;

            try
            {
                using var icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath ?? "");
                if (icon != null)
                {
                    var src = Imaging.CreateBitmapSourceFromHIcon(icon.Handle, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions());
                    Logo.Source = src;
                    Icon = src;
                }
            }
            catch { /* no icon */ }

            DropZone.DragEnter += (_, e) =>
            {
                e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
                DropZone.BorderBrush = Hot;
                DropTitle.Text = "Let go to choose what to do";
                e.Handled = true;
            };
            DropZone.DragOver += (_, e) =>
            {
                e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
                e.Handled = true;
            };
            DropZone.DragLeave += (_, _) => ResetDropZone();
            DropZone.Drop += (_, e) =>
            {
                ResetDropZone();
                if (e.Data.GetData(DataFormats.FileDrop) is string[] files && files.Length > 0)
                {
                    e.Effects = DragDropEffects.Copy;
                    Dispatcher.InvokeAsync(() => _showWheel(files));
                }
            };

            Action onHistory = () => Dispatcher.InvokeAsync(LoadRecent);
            History.Changed += onHistory;
            Closed += (_, _) => History.Changed -= onHistory;
            LoadRecent();
            LoadSettings();
        }

        private void ResetDropZone()
        {
            DropZone.BorderBrush = Idle;
            DropTitle.Text = "Drop files here";
        }

        private void OnChoose(object sender, RoutedEventArgs e) => _chooseFiles();

        private void OnTab(object sender, RoutedEventArgs e)
        {
            bool recent = sender == RecentTab;
            RecentTab.IsChecked = recent;
            SettingsTab.IsChecked = !recent;
            RecentPanel.Visibility = recent ? Visibility.Visible : Visibility.Collapsed;
            SettingsPanel.Visibility = recent ? Visibility.Collapsed : Visibility.Visible;
            if (!recent) LoadSettings();
        }

        // ---------- Recent ----------

        private void LoadRecent()
        {
            var items = History.Load().Where(h => h.Exists).ToList();
            RecentList.ItemsSource = items;
            EmptyText.Visibility = items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        private static HistoryItem? ItemOf(object sender) => (sender as FrameworkElement)?.Tag as HistoryItem;

        private void OnOpenItem(object sender, RoutedEventArgs e)
        {
            var item = ItemOf(sender);
            var target = item?.Outputs.FirstOrDefault(o => File.Exists(o) || Directory.Exists(o));
            if (target != null) Start(target);
        }

        private void OnShowItem(object sender, RoutedEventArgs e)
        {
            var item = ItemOf(sender);
            var target = item?.Outputs.FirstOrDefault(o => File.Exists(o) || Directory.Exists(o));
            if (target != null)
            {
                try { Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{target}\"") { UseShellExecute = true }); }
                catch { }
            }
        }

        private void OnUndoItem(object sender, RoutedEventArgs e)
        {
            var item = ItemOf(sender);
            if (item == null) return;
            var names = string.Join("\n", item.Outputs.Select(o => Path.GetFileName(o.TrimEnd('\\'))).Take(5));
            if (MessageBox.Show(this, $"Delete the converted file(s)? Your original stays.\n\n{names}", "Undo",
                    MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            foreach (var o in item.Outputs)
            {
                try
                {
                    // To the Recycle Bin, so it can still be brought back.
                    if (File.Exists(o))
                        Microsoft.VisualBasic.FileIO.FileSystem.DeleteFile(o, Microsoft.VisualBasic.FileIO.UIOption.OnlyErrorDialogs,
                            Microsoft.VisualBasic.FileIO.RecycleOption.SendToRecycleBin);
                    else if (Directory.Exists(o))
                        Microsoft.VisualBasic.FileIO.FileSystem.DeleteDirectory(o, Microsoft.VisualBasic.FileIO.UIOption.OnlyErrorDialogs,
                            Microsoft.VisualBasic.FileIO.RecycleOption.SendToRecycleBin);
                }
                catch (Exception ex) { Log.Write("Undo: " + ex.Message); }
            }
            var list = History.Load();
            list.RemoveAll(h => h.Time == item.Time && h.Outputs.SequenceEqual(item.Outputs));
            History.Save(list);
        }

        // ---------- Settings ----------

        private bool _loading;

        private void LoadSettings()
        {
            _loading = true;
            ShiftDrag.IsChecked = Settings.WheelOnShiftDrag;
            ExplorerMenu.IsChecked = Settings.ExplorerMenu;
            SendTo.IsChecked = Settings.SendTo;
            StartWithWindows.IsChecked = Settings.StartsWithWindows;
            _loading = false;

            Ffmpeg.Refresh();
            LibreOffice.Refresh();
            var ff = Ffmpeg.Find();
            FfmpegStatus.Text = ff != null ? "Ready: " + ff : "Not installed. Needed for MP3, MP4 and other audio and video.";
            FfmpegButton.Visibility = ff != null ? Visibility.Collapsed : Visibility.Visible;

            var parts = new[] { ("Word", Office.HasWord), ("Excel", Office.HasExcel), ("PowerPoint", Office.HasPowerPoint) }
                .Where(p => p.Item2).Select(p => p.Item1).ToList();
            var lo = LibreOffice.Find();
            OfficeStatus.Text =
                (parts.Count > 0 ? "Microsoft " + string.Join(", ", parts) + " found and used first. " : "") +
                (lo != null ? "LibreOffice ready." : parts.Count == 3 ? "" : "Install the free LibreOffice to convert all office files.");
            OfficeButton.Visibility = lo != null ? Visibility.Collapsed : Visibility.Visible;

            try
            {
                var langs = Windows.Media.Ocr.OcrEngine.AvailableRecognizerLanguages.Select(l => l.DisplayName).ToList();
                OcrStatus.Text = langs.Count > 0
                    ? "Built into Windows. Languages: " + string.Join(", ", langs) + ". Add more in Settings, Time & language, Language."
                    : "Add a language with text recognition in Windows Settings, Time & language, Language.";
            }
            catch { OcrStatus.Text = "Not available on this version of Windows."; }

            var v = typeof(MainWindow).Assembly.GetName().Version;
            About.Text = $"DragWheel {v?.ToString(3)}. Free and open source (MIT). Everything runs on this PC.";
        }

        private void OnSetting(object sender, RoutedEventArgs e)
        {
            if (_loading) return;
            try
            {
                if (sender == ShiftDrag) Settings.WheelOnShiftDrag = ShiftDrag.IsChecked == true;
                else if (sender == ExplorerMenu) Settings.ExplorerMenu = ExplorerMenu.IsChecked == true;
                else if (sender == SendTo) Settings.SendTo = SendTo.IsChecked == true;
                else if (sender == StartWithWindows) Settings.StartsWithWindows = StartWithWindows.IsChecked == true;
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "Could not change this setting:\n" + ex.Message, "DragWheel");
            }
            LoadSettings();
        }

        private void OnInstallFfmpeg(object sender, RoutedEventArgs e) => Ffmpeg.InstallWithWinget();
        private void OnInstallOffice(object sender, RoutedEventArgs e) => LibreOffice.Install();
        private void OnRefresh(object sender, RoutedEventArgs e) => LoadSettings();
        private void OnHelp(object sender, RoutedEventArgs e) => Start("https://github.com/Harismushtaq12/DragWheel");
        private void OnLog(object sender, RoutedEventArgs e) => Start(Log.FilePath);

        private static void Start(string target)
        {
            try { Process.Start(new ProcessStartInfo(target) { UseShellExecute = true }); }
            catch { }
        }
    }
}
