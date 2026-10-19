using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Pipes;
using System.Threading.Tasks;

namespace DragWheel
{
    /// <summary>
    /// Only one DragWheel runs. When Explorer starts a second copy (right-click menu, Send to),
    /// it hands its files to the running copy through a named pipe and exits.
    /// </summary>
    internal static class SingleInstance
    {
        private static readonly string PipeName = "DragWheel.Pipe." + Environment.UserName;

        public static bool TrySend(IEnumerable<string> lines)
        {
            try
            {
                // Let the running copy come to the front (it needs our permission to take focus).
                NativeMethods.AllowSetForegroundWindow(NativeMethods.ASFW_ANY);
                using var client = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
                client.Connect(10000); // the running copy may be busy for a moment
                using var writer = new StreamWriter(client);
                foreach (var l in lines) writer.WriteLine(l);
                writer.Flush();
                return true;
            }
            catch
            {
                return false;
            }
        }

        public static void Listen(Action<List<string>> onMessage)
        {
            Task.Run(async () =>
            {
                while (true)
                {
                    try
                    {
                        using var server = new NamedPipeServerStream(PipeName, PipeDirection.In, 1,
                            PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
                        await server.WaitForConnectionAsync();
                        using var reader = new StreamReader(server);
                        var lines = new List<string>();
                        string? line;
                        while ((line = await reader.ReadLineAsync()) != null)
                            if (line.Length > 0) lines.Add(line);
                        if (lines.Count > 0) onMessage(lines);
                    }
                    catch (Exception e)
                    {
                        Log.Write("Pipe: " + e.Message);
                        await Task.Delay(500);
                    }
                }
            });
        }
    }
}
