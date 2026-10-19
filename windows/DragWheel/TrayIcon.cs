using System;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;

namespace DragWheel
{
    /// <summary>The icon near the clock, with its right-click menu and notifications.</summary>
    internal sealed class TrayIcon : IDisposable
    {
        private readonly NotifyIcon _icon;
        private readonly ToolStripMenuItem _startWithWindows;
        private readonly ToolStripMenuItem _ffmpegItem;

        public TrayIcon()
        {
            var menu = new ContextMenuStrip();

            menu.Items.Add(new ToolStripMenuItem("Hold Shift while dragging a file") { Enabled = false });
            menu.Items.Add(new ToolStripMenuItem("Rest on the centre to switch to Tools") { Enabled = false });
            menu.Items.Add(new ToolStripSeparator());

            _startWithWindows = new ToolStripMenuItem("Start with Windows") { CheckOnClick = true, Checked = Settings.StartsWithWindows };
            _startWithWindows.Click += (_, _) => Settings.StartsWithWindows = _startWithWindows.Checked;
            menu.Items.Add(_startWithWindows);

            _ffmpegItem = new ToolStripMenuItem("Install ffmpeg (for audio and video)");
            _ffmpegItem.Click += (_, _) => Ffmpeg.InstallWithWinget();
            menu.Items.Add(_ffmpegItem);

            var help = new ToolStripMenuItem("Help and source code");
            help.Click += (_, _) => Open("https://github.com/Harismushtaq12/DragWheel");
            menu.Items.Add(help);

            var log = new ToolStripMenuItem("Open error log");
            log.Click += (_, _) => Open(Log.FilePath);
            menu.Items.Add(log);

            menu.Items.Add(new ToolStripSeparator());
            var quit = new ToolStripMenuItem("Quit DragWheel");
            quit.Click += (_, _) => System.Windows.Application.Current.Shutdown();
            menu.Items.Add(quit);

            menu.Opening += (_, _) =>
            {
                var found = Ffmpeg.Find() != null;
                _ffmpegItem.Text = found ? "ffmpeg is installed" : "Install ffmpeg (for audio and video)";
                _ffmpegItem.Enabled = !found;
                _startWithWindows.Checked = Settings.StartsWithWindows;
            };

            _icon = new NotifyIcon
            {
                Icon = LoadIcon(),
                Text = "DragWheel: hold Shift while dragging a file",
                ContextMenuStrip = menu,
                Visible = true
            };
        }

        public void Notify(string title, string text, bool isError = false)
        {
            if (string.IsNullOrWhiteSpace(text)) text = " ";
            if (text.Length > 250) text = text.Substring(0, 250) + "…";
            _icon.ShowBalloonTip(4000, title, text, isError ? ToolTipIcon.Error : ToolTipIcon.Info);
        }

        private static Icon LoadIcon()
        {
            try
            {
                var exe = Environment.ProcessPath;
                if (exe != null)
                {
                    var icon = Icon.ExtractAssociatedIcon(exe);
                    if (icon != null) return icon;
                }
            }
            catch { /* fall back below */ }
            return SystemIcons.Application;
        }

        private static void Open(string target)
        {
            try { Process.Start(new ProcessStartInfo(target) { UseShellExecute = true }); }
            catch { /* nothing to open */ }
        }

        public void Dispose()
        {
            _icon.Visible = false;
            _icon.Dispose();
        }
    }
}
