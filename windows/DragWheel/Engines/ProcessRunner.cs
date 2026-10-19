using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace DragWheel.Engines
{
    /// <summary>Runs a command-line program without a window and reports its errors in plain words.</summary>
    internal static class ProcessRunner
    {
        public static async Task Run(string exe, IEnumerable<string> args, TimeSpan timeout, string what)
        {
            var info = new ProcessStartInfo(exe)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
                RedirectStandardOutput = true,
            };
            foreach (var a in args) info.ArgumentList.Add(a);

            using var process = new Process { StartInfo = info };
            var errors = new StringBuilder();
            process.ErrorDataReceived += (_, e) => { if (e.Data != null) lock (errors) errors.AppendLine(e.Data); };
            process.OutputDataReceived += (_, e) => { if (e.Data != null) lock (errors) errors.AppendLine(e.Data); };
            process.Start();
            process.BeginErrorReadLine();
            process.BeginOutputReadLine();

            using var cts = new CancellationTokenSource(timeout);
            try
            {
                await process.WaitForExitAsync(cts.Token);
            }
            catch (OperationCanceledException)
            {
                try { process.Kill(entireProcessTree: true); } catch { }
                throw new InvalidOperationException($"{what} took too long and was stopped.");
            }

            if (process.ExitCode != 0)
            {
                var lines = errors.ToString().Trim().Split('\n');
                var last = string.Join(" ", lines.Skip(Math.Max(0, lines.Length - 2))).Trim();
                throw new InvalidOperationException(last.Length > 0 ? last : $"{what} stopped with code {process.ExitCode}.");
            }
        }

        /// <summary>Opens a console window that installs a program with winget.</summary>
        public static void WingetInstall(string id)
        {
            Process.Start(new ProcessStartInfo("cmd.exe",
                $"/k winget install --id {id} -e --accept-source-agreements --accept-package-agreements && echo. && echo Done. You can close this window.")
            {
                UseShellExecute = true
            });
        }
    }
}
