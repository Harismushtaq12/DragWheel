using System;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace DragWheel
{
    /// <summary>What a job works on: the files, and the answer to its question (if it asked one).</summary>
    public sealed record Job(IReadOnlyList<string> Files, string? Answer);

    /// <summary>A question asked before the action runs, e.g. a password.</summary>
    public sealed record Ask(string Question, string Default = "", bool Password = false);

    /// <summary>Extra programs some actions need.</summary>
    public enum Engine { None, Ffmpeg, Office }

    /// <summary>One item on the wheel.</summary>
    /// <param name="Title">Short label on the wheel, e.g. "JPG" or "Compress".</param>
    /// <param name="Hint">One or two words under the title.</param>
    /// <param name="Done">Notification title when it finishes.</param>
    /// <param name="Run">Does the work on a background thread.</param>
    public sealed record WheelAction(
        string Title,
        string Hint,
        string Done,
        Func<Job, Task<ActionResult>> Run,
        Engine Engine = Engine.None,
        Ask? Ask = null);

    public sealed class ActionResult
    {
        public List<string> Files { get; } = new();
        /// <summary>"name: reason" for each file that failed, when others worked.</summary>
        public List<string> Failed { get; } = new();
        public string? ClipboardText { get; init; }
        public string? Message { get; init; }

        public static ActionResult Of(IEnumerable<string> files)
        {
            var r = new ActionResult();
            r.Files.AddRange(files);
            return r;
        }

        public static ActionResult Of(string file) => Of(new[] { file });
    }
}
