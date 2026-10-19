using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace DragWheel.Engines
{
    /// <summary>
    /// The free LibreOffice suite, used when Microsoft Office is not installed (and for ODT/ODS/ODP).
    /// The tray menu and settings can install it with winget.
    /// </summary>
    internal static class LibreOffice
    {
        private static string? _cached;
        private static bool _searched;

        public static string? Find()
        {
            if (_searched && (_cached == null || File.Exists(_cached))) return _cached;
            _cached = Search();
            _searched = true;
            return _cached;
        }

        public static void Refresh() => _searched = false;

        private static string? Search()
        {
            foreach (var root in new[]
            {
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
            })
            {
                if (string.IsNullOrEmpty(root)) continue;
                var exe = Path.Combine(root, "LibreOffice", "program", "soffice.exe");
                if (File.Exists(exe)) return exe;
            }
            var path = Environment.GetEnvironmentVariable("PATH") ?? "";
            foreach (var dir in path.Split(';', StringSplitOptions.RemoveEmptyEntries))
            {
                try
                {
                    var exe = Path.Combine(dir.Trim().Trim('"'), "soffice.exe");
                    if (File.Exists(exe)) return exe;
                }
                catch { /* bad PATH entry */ }
            }
            return null;
        }

        public static void Install()
        {
            ProcessRunner.WingetInstall("TheDocumentFoundation.LibreOffice");
            Refresh();
        }

        /// <summary>
        /// Converts [input] with LibreOffice. [spec] is LibreOffice's target, e.g. "pdf" or "docx:MS Word 2007 XML".
        /// The result is moved to [output].
        /// </summary>
        private static readonly System.Threading.SemaphoreSlim Gate = new(1, 1);

        public static async Task Convert(string input, string spec, string output)
        {
            // LibreOffice runs one conversion at a time with our profile.
            await Gate.WaitAsync();
            try { await ConvertNow(input, spec, output); }
            finally { Gate.Release(); }
        }

        private static async Task ConvertNow(string input, string spec, string output)
        {
            var exe = Find() ?? throw new InvalidOperationException(
                "This needs Microsoft Office or the free LibreOffice. Right-click the DragWheel icon and choose Install LibreOffice.");

            var temp = Path.Combine(Path.GetTempPath(), "dragwheel-lo-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(temp);
            try
            {
                // A private profile, so this works even while LibreOffice is open.
                var profile = new Uri(Path.Combine(Settings.DataFolder, "lo-profile") + Path.DirectorySeparatorChar).AbsoluteUri;
                await ProcessRunner.Run(exe, new[]
                {
                    "-env:UserInstallation=" + profile,
                    "--headless", "--norestore", "--nolockcheck", "--nodefault",
                    "--convert-to", spec,
                    "--outdir", temp,
                    Path.GetFullPath(input),
                }, TimeSpan.FromMinutes(5), "LibreOffice");

                var ext = "." + spec.Split(':')[0];
                var made = Directory.GetFiles(temp).FirstOrDefault(f => f.EndsWith(ext, StringComparison.OrdinalIgnoreCase))
                    ?? Directory.GetFiles(temp).FirstOrDefault()
                    ?? throw new InvalidOperationException("LibreOffice could not convert this file.");
                File.Move(made, output);
            }
            finally
            {
                try { Directory.Delete(temp, true); } catch { }
            }
        }
    }
}
