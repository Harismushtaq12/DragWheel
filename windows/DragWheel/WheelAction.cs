using System;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace DragWheel
{
    /// <summary>One item on the wheel.</summary>
    /// <param name="Title">Short label on the wheel, e.g. "JPG" or "Compress".</param>
    /// <param name="Hint">One or two words under the title, e.g. "smaller file".</param>
    /// <param name="Done">Notification title when it finishes, e.g. "Converted".</param>
    /// <param name="Run">Does the work on a background thread.</param>
    /// <param name="NeedsFfmpeg">True for audio and video actions.</param>
    public sealed record WheelAction(
        string Title,
        string Hint,
        string Done,
        Func<IReadOnlyList<string>, Task<ActionResult>> Run,
        bool NeedsFfmpeg = false);

    public sealed class ActionResult
    {
        public List<string> Files { get; } = new();
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
