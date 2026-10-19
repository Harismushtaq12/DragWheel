using System;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;
using DragWheel.Engines;

namespace DragWheel
{
    /// <summary>The icon near the clock, with its right-click menu and notifications.</summary>
    internal sealed class TrayIcon : IDisposable
    {
        private readonly NotifyIcon _icon;
        private readonly ToolStripMenuItem _ffmpegItem;
        private readonly ToolStripMenuItem _officeItem;

        public TrayIcon(Action openWindow, Action chooseFiles)
        {
            var menu = new ContextMenuStrip();

            var open = new ToolStripMenuItem("Open DragWheel") { Font = new Font(menu.Font, FontStyle.Bold) };
            open.Click += (_, _) => openWindow();
            menu.Items.Add(open);

            var choose = new ToolStripMenuItem("Convert files…");
            choose.Click += (_, _) => chooseFiles();
            menu.Items.Add(choose);

            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("Shift-drag a file, or right-click it and choose Convert with DragWheel") { Enabled = false });
            menu.Items.Add(new ToolStripSeparator());

            _ffmpegItem = new ToolStripMenuItem("Install ffmpeg (audio and video)");
            _ffmpegItem.Click += (_, _) => Ffmpeg.InstallWithWinget();
            menu.Items.Add(_ffmpegItem);

            _officeItem = new ToolStripMenuItem("Install LibreOffice (Word, Excel, PowerPoint files)");
            _officeItem.Click += (_, _) => LibreOffice.Install();
            menu.Items.Add(_officeItem);

            var log = new ToolStripMenuItem("Open error log");
            log.Click += (_, _) => Open(Log.FilePath);
            menu.Items.Add(log);

            menu.Items.Add(new ToolStripSeparator());
            var quit = new ToolStripMenuItem("Quit DragWheel");
            quit.Click += (_, _) => System.Windows.Application.Current.Shutdown();
            menu.Items.Add(quit);

            menu.Opening += (_, _) =>
            {
                Ffmpeg.Refresh();
                LibreOffice.Refresh();
                bool ff = Ffmpeg.Find() != null;
                _ffmpegItem.Text = ff ? "ffmpeg is installed" : "Install ffmpeg (audio and video)";
                _ffmpegItem.Enabled = !ff;
                bool lo = LibreOffice.Find() != null;
                bool office = Office.HasWord && Office.HasExcel && Office.HasPowerPoint;
                _officeItem.Text = lo ? "LibreOffice is installed" : office ? "Microsoft Office is used for documents" : "Install LibreOffice (Word, Excel, PowerPoint files)";
                _officeItem.Enabled = !lo && !office;
            };

            _icon = new NotifyIcon
            {
                Icon = LoadIcon(),
                Text = "DragWheel: hold Shift while dragging a file",
                ContextMenuStrip = menu,
                Visible = true
            };
            _icon.DoubleClick += (_, _) => openWindow();
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
