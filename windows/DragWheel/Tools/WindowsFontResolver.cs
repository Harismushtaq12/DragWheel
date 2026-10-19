using System;
using System.IO;
using PdfSharp.Fonts;

namespace DragWheel.Tools
{
    /// <summary>Lets PDFsharp draw page numbers and watermarks with Arial from the Windows fonts folder.</summary>
    internal sealed class WindowsFontResolver : IFontResolver
    {
        private static readonly string Fonts = Environment.GetFolderPath(Environment.SpecialFolder.Fonts);

        public FontResolverInfo? ResolveTypeface(string familyName, bool bold, bool italic)
        {
            var file = (bold, italic) switch
            {
                (true, true) => "arialbi.ttf",
                (true, false) => "arialbd.ttf",
                (false, true) => "ariali.ttf",
                _ => "arial.ttf",
            };
            return new FontResolverInfo(file);
        }

        public byte[]? GetFont(string faceName)
        {
            foreach (var name in new[] { faceName, "arial.ttf", "segoeui.ttf" })
            {
                var path = Path.Combine(Fonts, name);
                if (File.Exists(path)) return File.ReadAllBytes(path);
            }
            return null;
        }
    }
}
