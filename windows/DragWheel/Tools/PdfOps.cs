using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using PdfSharp.Pdf;
using PdfSharp.Pdf.IO;
using Windows.Storage;
using Windows.Storage.Streams;
using WinPdf = Windows.Data.Pdf;

namespace DragWheel.Tools
{
    public static class PdfOps
    {
        private static PdfDocument Import(string path) => PdfReader.Open(path, PdfDocumentOpenMode.Import);

        public static string Merge(IReadOnlyList<string> paths)
        {
            var target = FileOps.NextTo(paths[0], "pdf", " merged");
            MergeTo(paths, target);
            return target;
        }

        public static void MergeTo(IReadOnlyList<string> paths, string target)
        {
            using var output = new PdfDocument();
            foreach (var p in paths)
            {
                using var input = Import(p);
                foreach (var page in input.Pages) output.AddPage(page);
            }
            output.Save(target);
        }

        public static string Split(string path)
        {
            using var input = Import(path);
            var folder = FileOps.FolderNextTo(path, " pages");
            Directory.CreateDirectory(folder);
            int digits = input.PageCount.ToString().Length;
            for (int i = 0; i < input.PageCount; i++)
            {
                using var single = new PdfDocument();
                single.AddPage(input.Pages[i]);
                single.Save(Path.Combine(folder, $"page {(i + 1).ToString().PadLeft(digits, '0')}.pdf"));
            }
            return folder;
        }

        public static string Reverse(string path)
        {
            using var input = Import(path);
            using var output = new PdfDocument();
            for (int i = input.PageCount - 1; i >= 0; i--) output.AddPage(input.Pages[i]);
            var target = FileOps.NextTo(path, "pdf", " reversed");
            output.Save(target);
            return target;
        }

        public static string Rotate(string path)
        {
            using var doc = PdfReader.Open(path, PdfDocumentOpenMode.Modify);
            foreach (var page in doc.Pages) page.Rotate = (page.Rotate + 90) % 360;
            var target = FileOps.NextTo(path, "pdf", " rotated");
            doc.Save(target);
            return target;
        }

        public static string Optimize(string path)
        {
            using var doc = PdfReader.Open(path, PdfDocumentOpenMode.Modify);
            doc.Options.CompressContentStreams = true;
            doc.Options.NoCompression = false;
            doc.Options.FlateEncodeMode = PdfFlateEncodeMode.BestCompression;
            var target = FileOps.NextTo(path, "pdf", " optimized");
            doc.Save(target);
            return target;
        }

        public static string ClearInfo(string path)
        {
            using var doc = PdfReader.Open(path, PdfDocumentOpenMode.Modify);
            doc.Info.Elements.Clear();
            var target = FileOps.NextTo(path, "pdf", " clean");
            doc.Save(target);
            return target;
        }

        /// <summary>Renders every page to an image with the PDF engine built into Windows.</summary>
        public static async Task<string> RenderPages(string path, bool png, string? folderOverride = null)
        {
            var file = await StorageFile.GetFileFromPathAsync(Path.GetFullPath(path));
            var doc = await WinPdf.PdfDocument.LoadFromFileAsync(file);

            var folder = folderOverride ?? FileOps.FolderNextTo(path, " images");
            Directory.CreateDirectory(folder);
            var ext = png ? "png" : "jpg";
            int digits = doc.PageCount.ToString().Length;

            for (uint i = 0; i < doc.PageCount; i++)
            {
                using var page = doc.GetPage(i);
                using var memory = new InMemoryRandomAccessStream();
                var options = new WinPdf.PdfPageRenderOptions
                {
                    // About 150 dpi: sharp on screen, reasonable size.
                    DestinationWidth = (uint)Math.Max(1, Math.Round(page.Size.Width * 150 / 72)),
                };
                if (!png) options.BitmapEncoderId = Windows.Graphics.Imaging.BitmapEncoder.JpegEncoderId;

                await page.RenderToStreamAsync(memory, options);
                memory.Seek(0);

                var target = Path.Combine(folder, $"page {(i + 1).ToString().PadLeft(digits, '0')}.{ext}");
                using var fileStream = File.Create(target);
                await memory.AsStreamForRead().CopyToAsync(fileStream);
            }
            return folder;
        }
    }
}
