using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace DragWheel
{
    /// <summary>
    /// Finds and runs ffmpeg. DragWheel does not ship ffmpeg; the tray menu can install it with winget.
    /// </summary>
    public static class Ffmpeg
    {
        private static string? _cached;
        private static bool _searched;

        /// <summary>Cached, so it is cheap to call while drawing the wheel.</summary>
        public static string? Find()
        {
            if (_searched && (_cached == null || File.Exists(_cached))) return _cached;
            _cached = Search();
            _searched = true;
            return _cached;
        }

        /// <summary>Forget the cached answer, e.g. after the user may have installed ffmpeg.</summary>
        public static void Refresh() => _searched = false;

        private static string? Search()
        {
            // 1. Next to DragWheel.exe
            var local = Path.Combine(AppContext.BaseDirectory, "ffmpeg.exe");
            if (File.Exists(local)) return local;

            // 2. On PATH
            var path = Environment.GetEnvironmentVariable("PATH") ?? "";
            foreach (var dir in path.Split(';', StringSplitOptions.RemoveEmptyEntries))
            {
                try
                {
                    var candidate = Path.Combine(dir.Trim().Trim('"'), "ffmpeg.exe");
                    if (File.Exists(candidate)) return candidate;
                }
                catch { /* bad PATH entry */ }
            }

            // 3. Installed by winget (our PATH may be older than the install).
            var appData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var link = Path.Combine(appData, "Microsoft", "WinGet", "Links", "ffmpeg.exe");
            if (File.Exists(link)) return link;

            var packages = Path.Combine(appData, "Microsoft", "WinGet", "Packages");
            if (Directory.Exists(packages))
            {
                try
                {
                    foreach (var pkg in Directory.GetDirectories(packages, "*FFmpeg*"))
                    {
                        var exe = Directory.EnumerateFiles(pkg, "ffmpeg.exe", SearchOption.AllDirectories).FirstOrDefault();
                        if (exe != null) return exe;
                    }
                }
                catch { /* access denied */ }
            }
            return null;
        }

        public static async Task Run(string input, string output, string[] args)
        {
            var exe = Find() ?? throw new InvalidOperationException(
                "ffmpeg is not installed. Right-click the DragWheel icon and choose Install ffmpeg.");

            var info = new ProcessStartInfo(exe)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
                RedirectStandardOutput = true,
            };
            info.ArgumentList.Add("-hide_banner");
            info.ArgumentList.Add("-nostdin");
            info.ArgumentList.Add("-y");
            info.ArgumentList.Add("-i");
            info.ArgumentList.Add(input);
            foreach (var a in args) info.ArgumentList.Add(a);
            info.ArgumentList.Add(output);

            using var process = new Process { StartInfo = info };
            var errors = new StringBuilder();
            process.ErrorDataReceived += (_, e) => { if (e.Data != null) lock (errors) errors.AppendLine(e.Data); };
            process.OutputDataReceived += (_, _) => { };

            process.Start();
            process.BeginErrorReadLine();
            process.BeginOutputReadLine();
            await process.WaitForExitAsync();

            if (process.ExitCode != 0)
            {
                try { if (File.Exists(output)) File.Delete(output); } catch { }
                var lines = errors.ToString().Trim().Split('\n');
                var last = string.Join(" ", lines.Skip(Math.Max(0, lines.Length - 2))).Trim();
                throw new InvalidOperationException(last.Length > 0 ? last : $"ffmpeg stopped with code {process.ExitCode}.");
            }
        }

        public static void InstallWithWinget()
        {
            Process.Start(new ProcessStartInfo("cmd.exe",
                "/k winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements && echo. && echo Done. You can close this window.")
            {
                UseShellExecute = true
            });
            Refresh();
        }
    }
}
