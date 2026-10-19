using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using PdfSharp.Drawing;
using PdfSharp.Pdf;
using PdfSharp.Pdf.IO;
using Windows.Storage.Streams;
using WinPdf = Windows.Data.Pdf;

namespace DragWheel.Tools
{
    /// <summary>Page numbers, watermark, password, and pages as pictures.</summary>
    public static class PdfExtras
    {
        public sealed record PageImage(byte[] Jpeg, double WidthPt, double HeightPt);

        /// <summary>Every page as a JPEG (150 dpi), with its size in points.</summary>
        public static async Task<List<PageImage>> RenderPageBytes(string path)
        {
            using var source = File.OpenRead(path);
            var doc = await WinPdf.PdfDocument.LoadFromStreamAsync(source.AsRandomAccessStream());
            var list = new List<PageImage>();
            for (uint i = 0; i < doc.PageCount; i++)
            {
                using var page = doc.GetPage(i);
                using var memory = new InMemoryRandomAccessStream();
                var options = new WinPdf.PdfPageRenderOptions
                {
                    DestinationWidth = (uint)Math.Max(1, Math.Round(page.Size.Width * 150 / 96)),
                    BitmapEncoderId = Windows.Graphics.Imaging.BitmapEncoder.JpegEncoderId,
                };
                await page.RenderToStreamAsync(memory, options);
                memory.Seek(0);
                using var ms = new MemoryStream();
                await memory.AsStreamForRead().CopyToAsync(ms);
                // Windows gives page sizes in 1/96 inch; Word wants points (1/72 inch).
                list.Add(new PageImage(ms.ToArray(), page.Size.Width * 72 / 96, page.Size.Height * 72 / 96));
            }
            return list;
        }

        /// <summary>Every page as a PNG stream for reading text. The caller disposes them.</summary>
        public static async Task<List<byte[]>> RenderPagePngs(string path, uint maxSide)
        {
            using var source = File.OpenRead(path);
            var doc = await WinPdf.PdfDocument.LoadFromStreamAsync(source.AsRandomAccessStream());
            var list = new List<byte[]>();
            for (uint i = 0; i < doc.PageCount; i++)
            {
                using var page = doc.GetPage(i);
                using var memory = new InMemoryRandomAccessStream();
                double scale = Math.Min(200.0 / 96, maxSide / Math.Max(page.Size.Width, page.Size.Height));
                var options = new WinPdf.PdfPageRenderOptions { DestinationWidth = (uint)Math.Max(1, page.Size.Width * scale) };
                await page.RenderToStreamAsync(memory, options);
                memory.Seek(0);
                using var ms = new MemoryStream();
                await memory.AsStreamForRead().CopyToAsync(ms);
                list.Add(ms.ToArray());
            }
            return list;
        }

        public static string PageNumbers(string path)
        {
            using var doc = PdfReader.Open(path, PdfDocumentOpenMode.Modify);
            var font = new XFont("Arial", 10);
            int n = doc.PageCount;
            for (int i = 0; i < n; i++)
            {
                var page = doc.Pages[i];
                using var gfx = XGraphics.FromPdfPage(page, XGraphicsPdfPageOptions.Append);
                var text = $"{i + 1} / {n}";
                var size = gfx.MeasureString(text, font);
                var (left, top, width, height) = Visible(page);
                gfx.DrawString(text, font, XBrushes.DimGray, new XPoint(left + (width - size.Width) / 2, top + height - 22));
            }
            var target = FileOps.NextTo(path, "pdf", " numbered");
            doc.Save(target);
            return target;
        }

        public static string Watermark(string path, string text)
        {
            if (string.IsNullOrWhiteSpace(text)) throw new InvalidOperationException("Type the watermark text.");
            using var doc = PdfReader.Open(path, PdfDocumentOpenMode.Modify);
            foreach (var page in doc.Pages)
            {
                using var gfx = XGraphics.FromPdfPage(page, XGraphicsPdfPageOptions.Append);
                var (left, top, w, h) = Visible(page);
                double size = Math.Min(w, h) / Math.Max(6, text.Length) * 1.6;
                var font = new XFont("Arial", size, XFontStyleEx.Bold);
                var brush = new XSolidBrush(XColor.FromArgb(48, 128, 128, 128));
                var measured = gfx.MeasureString(text, font);
                gfx.TranslateTransform(left + w / 2, top + h / 2);
                gfx.RotateTransform(-35);
                gfx.DrawString(text, font, brush, new XPoint(-measured.Width / 2, measured.Height / 3));
            }
            var target = FileOps.NextTo(path, "pdf", " watermarked");
            doc.Save(target);
            return target;
        }

        /// <summary>The part of the page that is shown (crop box), in drawing units from the top-left.</summary>
        private static (double Left, double Top, double Width, double Height) Visible(PdfPage page)
        {
            var media = page.MediaBox;
            var crop = page.CropBox;
            if (crop.IsEmpty || crop.Width <= 0 || crop.Height <= 0) return (0, 0, media.Width, media.Height);
            return (crop.X1 - media.X1, media.Y2 - crop.Y2, crop.Width, crop.Height);
        }

        public static string Lock(string path, string password)
        {
            if (password.Length < 4) throw new InvalidOperationException("Use a password with at least 4 characters.");
            using var doc = PdfReader.Open(path, PdfDocumentOpenMode.Modify);
            doc.SecuritySettings.UserPassword = password;
            doc.SecuritySettings.OwnerPassword = password;
            var target = FileOps.NextTo(path, "pdf", " locked");
            doc.Save(target);
            return target;
        }
    }
}
