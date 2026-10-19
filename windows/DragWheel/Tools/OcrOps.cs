using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using ImageMagick;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;
using Windows.Storage.Streams;

namespace DragWheel.Tools
{
    /// <summary>
    /// Reads printed words from pictures and scanned PDFs with the text reader built into Windows.
    /// It reads the languages installed in Windows settings (Time &amp; language).
    /// </summary>
    public static class OcrOps
    {
        private static OcrEngine Engine() =>
            OcrEngine.TryCreateFromUserProfileLanguages()
            ?? throw new InvalidOperationException("Windows has no text-reading language installed. Add one in Settings, Time & language, Language.");

        private static async Task<string> Read(OcrEngine engine, byte[] picture)
        {
            using var ras = new InMemoryRandomAccessStream();
            using (var s = ras.AsStreamForWrite())
            {
                await s.WriteAsync(picture);
                await s.FlushAsync();
            }
            ras.Seek(0);
            var decoder = await BitmapDecoder.CreateAsync(ras);
            using var bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
            var result = await engine.RecognizeAsync(bitmap);
            return string.Join(Environment.NewLine, result.Lines.Select(l => l.Text));
        }

        public static async Task<string> ImageToText(string path)
        {
            var engine = Engine();
            byte[] png;
            using (var img = new MagickImage(path))
            {
                img.AutoOrient();
                var max = OcrEngine.MaxImageDimension;
                if (img.Width > max || img.Height > max) img.Resize(new MagickGeometry(max, max));
                png = img.ToByteArray(MagickFormat.Png);
            }
            var text = (await Read(engine, png)).Trim();
            if (text.Length == 0) throw new InvalidOperationException("No words were found in this picture.");
            var output = FileOps.NextTo(path, "txt");
            await File.WriteAllTextAsync(output, text, new UTF8Encoding(true));
            return output;
        }

        public static async Task<string> PdfToText(string path)
        {
            var engine = Engine();
            var pages = await PdfExtras.RenderPagePngs(path, OcrEngine.MaxImageDimension);
            var sb = new StringBuilder();
            for (int i = 0; i < pages.Count; i++)
            {
                if (pages.Count > 1) sb.AppendLine($"--- Page {i + 1} ---");
                sb.AppendLine((await Read(engine, pages[i])).Trim());
                sb.AppendLine();
            }
            var text = sb.ToString().Trim();
            if (text.Replace("---", "").Replace("Page", "").Trim().Length < 3)
                throw new InvalidOperationException("No words were found in this PDF.");
            var output = FileOps.NextTo(path, "txt", " (read)");
            await File.WriteAllTextAsync(output, text, new UTF8Encoding(true));
            return output;
        }
    }
}
