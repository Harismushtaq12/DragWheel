using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using UglyToad.PdfPig.Content;
using UglyToad.PdfPig.DocumentLayoutAnalysis;
using UglyToad.PdfPig.DocumentLayoutAnalysis.PageSegmenter;
using UglyToad.PdfPig.DocumentLayoutAnalysis.ReadingOrderDetector;
using UglyToad.PdfPig.DocumentLayoutAnalysis.WordExtractor;
using PigDocument = UglyToad.PdfPig.PdfDocument;

namespace DragWheel.Tools
{
    /// <summary>PDF to Word and text, and pictures out of PDFs. Uses PdfPig (Apache-2.0).</summary>
    public static class PdfWordOps
    {

        /// <summary>Text blocks of a page, in reading order (columns read top to bottom).</summary>
        private static List<TextBlock> Blocks(Page page)
        {
            var words = page.GetWords(NearestNeighbourWordExtractor.Instance).ToList();
            if (words.Count == 0) return new List<TextBlock>();
            var blocks = DocstrumBoundingBoxes.Instance.GetBlocks(words);
            return UnsupervisedReadingOrderDetector.Instance.Get(blocks).ToList();
        }

        private static (bool Bold, bool Italic, double Size) Style(Word w)
        {
            var first = w.Letters.Count > 0 ? w.Letters[0] : null;
            var font = first?.FontName ?? "";
            bool bold = font.Contains("Bold", StringComparison.OrdinalIgnoreCase) || font.Contains("Black", StringComparison.OrdinalIgnoreCase);
            bool italic = font.Contains("Italic", StringComparison.OrdinalIgnoreCase) || font.Contains("Oblique", StringComparison.OrdinalIgnoreCase);
            double size = w.Letters.Count > 0 ? w.Letters.Max(l => l.PointSize) : 11;
            return (bold, italic, size);
        }

        /// <summary>An editable Word file: words, paragraphs, bold/italic, bigger titles, and the pictures.</summary>
        public static string ToWord(string path)
        {
            using var pdf = PigDocument.Open(path);
            var pages = pdf.GetPages().ToList();

            // The most common letter size is normal body text.
            var sizes = new Dictionary<int, int>();
            foreach (var p in pages)
                foreach (var l in p.Letters)
                {
                    var k = (int)Math.Round(l.PointSize);
                    sizes[k] = sizes.TryGetValue(k, out var n) ? n + 1 : 1;
                }
            if (sizes.Count == 0)
                throw new InvalidOperationException("No text found. This PDF is probably scanned pictures. Try \"Word (exact)\" or \"Read text\".");
            double body = sizes.OrderByDescending(kv => kv.Value).First().Key;

            var w = new DocxWriter();
            for (int i = 0; i < pages.Count; i++)
            {
                bool firstOnPage = true;
                foreach (var block in Blocks(pages[i]))
                {
                    var runs = new List<DocRun>();
                    double biggest = 0;
                    foreach (var line in block.TextLines)
                    {
                        foreach (var word in line.Words)
                        {
                            var (bold, italic, size) = Style(word);
                            biggest = Math.Max(biggest, size);
                            double? sz = Math.Abs(size - body) < 1 ? null : Math.Round(size * 2) / 2;
                            var last = runs.Count > 0 ? runs[^1] : null;
                            if (last != null && last.Bold == bold && last.Italic == italic && last.SizePt == sz)
                                runs[^1] = last with { Text = last.Text + " " + word.Text };
                            else
                                runs.Add(new DocRun((runs.Count > 0 ? " " : "") + word.Text, bold, italic, sz));
                        }
                    }
                    bool heading = biggest >= body * 1.3;
                    w.Paragraph(runs, heading ? 1 : 0, heading ? 10 : 6, pageBreakBefore: firstOnPage && i > 0);
                    firstOnPage = false;
                }

                foreach (var img in pages[i].GetImages())
                {
                    var bytes = ImageBytes(img, out var ext);
                    if (bytes == null) continue;
                    var b = img.Bounds;
                    double width = Math.Min(w.PageWidthPt - 2 * w.MarginPt, Math.Max(20, b.Width));
                    double height = Math.Max(20, b.Height) * (width / Math.Max(20, b.Width));
                    w.Image(bytes, ext, width, Math.Min(height, w.PageHeightPt - 2 * w.MarginPt), pageBreakBefore: firstOnPage && i > 0);
                    firstOnPage = false;
                }
            }
            var output = FileOps.NextTo(path, "docx");
            w.Save(output);
            return output;
        }

        private static byte[]? ImageBytes(IPdfImage img, out string ext)
        {
            if (img.TryGetPng(out var png))
            {
                ext = "png";
                return png;
            }
            // JPEG pictures are stored as complete JPEG files.
            var raw = img.RawBytes.ToArray();
            ext = "jpeg";
            return raw.Length > 3 && raw[0] == 0xFF && raw[1] == 0xD8 ? raw : null;
        }

        /// <summary>Plain text, in reading order, with a line between pages.</summary>
        public static string ToText(string path)
        {
            using var pdf = PigDocument.Open(path);
            var sb = new StringBuilder();
            foreach (var page in pdf.GetPages())
            {
                foreach (var block in Blocks(page))
                {
                    sb.AppendLine(string.Join("\n", block.TextLines.Select(l => l.Text)));
                    sb.AppendLine();
                }
                sb.AppendLine("\f");
            }
            var text = sb.ToString().Trim();
            if (text.Replace("\f", "").Trim().Length == 0)
                throw new InvalidOperationException("No text found. This PDF is probably scanned pictures. Try \"Read text\".");
            var output = FileOps.NextTo(path, "txt");
            File.WriteAllText(output, text, new UTF8Encoding(true));
            return output;
        }

        /// <summary>Saves the pictures inside the PDF in a folder next to it.</summary>
        public static string ExtractImages(string path)
        {
            using var pdf = PigDocument.Open(path);
            var folder = FileOps.FolderNextTo(path, " pictures");
            int count = 0;
            foreach (var page in pdf.GetPages())
            {
                int n = 0;
                foreach (var img in page.GetImages())
                {
                    var bytes = ImageBytes(img, out var ext);
                    if (bytes == null) continue;
                    Directory.CreateDirectory(folder);
                    n++;
                    count++;
                    File.WriteAllBytes(Path.Combine(folder, $"page {page.Number} picture {n}.{(ext == "jpeg" ? "jpg" : ext)}"), bytes);
                }
            }
            if (count == 0) throw new InvalidOperationException("No pictures that can be saved were found inside this PDF.");
            return folder;
        }

        /// <summary>Word file where every page is a picture of the PDF page: looks the same, not editable.</summary>
        public static async Task<string> ToWordExact(string path)
        {
            var pages = await PdfExtras.RenderPageBytes(path);
            var w = new DocxWriter { MarginPt = 0 };
            if (pages.Count > 0)
            {
                w.PageWidthPt = pages[0].WidthPt;
                w.PageHeightPt = pages[0].HeightPt;
            }
            for (int i = 0; i < pages.Count; i++)
            {
                var p = pages[i];
                double s = Math.Min(w.PageWidthPt / p.WidthPt, w.PageHeightPt / p.HeightPt) * 0.97;
                w.Image(p.Jpeg, "jpeg", p.WidthPt * s, p.HeightPt * s, pageBreakBefore: i > 0);
            }
            var output = FileOps.NextTo(path, "docx", " (exact)");
            w.Save(output);
            return output;
        }
    }
}
