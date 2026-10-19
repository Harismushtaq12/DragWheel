using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using DragWheel.Engines;
using DragWheel.Tools;

namespace DragWheel
{
    /// <summary>
    /// Decides what appears on the wheel for the dragged files, in two rings:
    /// Convert (every format the files can become) and Tools (everything else).
    /// To add a feature, add a WheelAction to the right list. Nothing else needs to change.
    /// </summary>
    public static class Catalog
    {
        private const int MaxItems = 12;

        private static readonly HashSet<string> ImageExt = Set("jpg jpeg jfif png webp heic heif avif bmp gif tif tiff ico psd cr2 cr3 nef arw dng raf orf rw2 svg jxl");
        private static readonly HashSet<string> AudioExt = Set("mp3 m4a m4b wav flac ogg opus aac wma aif aiff amr");
        private static readonly HashSet<string> VideoExt = Set("mp4 mov mkv webm avi wmv m4v flv 3gp mts m2ts ts mpg mpeg");
        private static readonly HashSet<string> ArchiveExt = Set("zip 7z rar tar gz tgz");
        private static readonly HashSet<string> TextExt = Set("txt md markdown log ini rtf html htm xml");
        private static readonly HashSet<string> SubtitleExt = Set("srt vtt");

        public enum Kind { Image, Audio, Video, Pdf, Word, Sheet, Slides, Csv, Json, Text, Subtitle, Archive, Folder, Other }

        public static Kind KindOf(string path)
        {
            if (Directory.Exists(path)) return Kind.Folder;
            var ext = Ext(path);
            if (ext == "pdf") return Kind.Pdf;
            if (ext == "csv" || ext == "tsv") return Kind.Csv;
            if (ext == "json") return Kind.Json;
            switch (OfficeOps.FamilyOf(path))
            {
                case OfficeOps.Family.Word: return Kind.Word;
                case OfficeOps.Family.Sheet: return Kind.Sheet;
                case OfficeOps.Family.Slides: return Kind.Slides;
            }
            if (ImageExt.Contains(ext)) return Kind.Image;
            if (AudioExt.Contains(ext)) return Kind.Audio;
            if (VideoExt.Contains(ext)) return Kind.Video;
            if (SubtitleExt.Contains(ext)) return Kind.Subtitle;
            if (TextExt.Contains(ext)) return Kind.Text;
            if (ArchiveExt.Contains(ext)) return Kind.Archive;
            if (path.EndsWith(".tar.bz2", StringComparison.OrdinalIgnoreCase) ||
                path.EndsWith(".tar.xz", StringComparison.OrdinalIgnoreCase)) return Kind.Archive;
            return Kind.Other;
        }

        private static bool Combinable(Kind k) => k is Kind.Image or Kind.Pdf or Kind.Word or Kind.Sheet or Kind.Slides;

        public static List<WheelAction> For(IReadOnlyList<string> files, bool tools)
        {
            var kinds = files.Select(KindOf).Distinct().ToList();
            var kind = kinds.Count == 1 ? kinds[0] : Kind.Other;
            var ext = Ext(files[0]);
            bool many = files.Count > 1;

            List<WheelAction> list;
            if (kind == Kind.Other && many && kinds.All(Combinable))
            {
                // A mix of pictures, PDFs and documents.
                list = tools ? new List<WheelAction>() : new List<WheelAction> { CombinePdf() };
            }
            else list = (kind, tools) switch
            {
                (Kind.Image, false) => ImageConvert(ext, files.Count),
                (Kind.Image, true) => ImageTools(files.Count),
                (Kind.Pdf, false) => PdfConvert(files.Count),
                (Kind.Pdf, true) => PdfTools(),
                (Kind.Word, false) => OfficeConvert(ext, "pdf docx odt rtf txt html", pictures: true),
                (Kind.Sheet, false) => SheetConvert(ext),
                (Kind.Slides, false) => OfficeConvert(ext, "pdf pptx odp", pictures: true),
                (Kind.Word or Kind.Sheet or Kind.Slides, true) => many ? new List<WheelAction> { CombinePdf() } : new List<WheelAction>(),
                (Kind.Csv, false) => new List<WheelAction> { Data("XLSX", "Excel", f => DataOps.CsvTo(f, "xlsx")), Data("JSON", "data", f => DataOps.CsvTo(f, "json")) },
                (Kind.Json, false) => new List<WheelAction> { Data("CSV", "table", f => DataOps.JsonTo(f, "csv")), Data("XLSX", "Excel", f => DataOps.JsonTo(f, "xlsx")) },
                (Kind.Text, false) => TextConvert(ext),
                (Kind.Subtitle, false) => SubtitleConvert(ext),
                (Kind.Audio, false) => AudioConversions(ext),
                (Kind.Audio, true) => AudioTools(),
                (Kind.Video, false) => VideoConversions(ext),
                (Kind.Video, true) => VideoTools(),
                (Kind.Archive, false) => ArchiveConvert(ext),
                (Kind.Folder, _) => new List<WheelAction> { Zip(), TarGz() },
                (_, false) => new List<WheelAction> { Zip(), TarGz() },
                _ => new List<WheelAction>(),
            };

            if (tools)
            {
                if (kind != Kind.Folder && list.All(a => a.Title != "Zip")) list.Add(Zip());
                if (kind != Kind.Folder) list.Add(Hash());
                list.Add(CopyPaths());
            }
            return list.Take(MaxItems).ToList();
        }

        private static string Ext(string path) => Path.GetExtension(path).TrimStart('.').ToLowerInvariant();

        private static HashSet<string> Set(string s) =>
            new(s.Split(' ', StringSplitOptions.RemoveEmptyEntries), StringComparer.OrdinalIgnoreCase);

        // ---------- Batch running ----------

        /// <summary>
        /// Runs [work] for every file, several at a time. One failure does not stop the others;
        /// failures are listed in the result.
        /// </summary>
        private static async Task<ActionResult> Each(IReadOnlyList<string> files, Func<string, Task<string>> work, int parallel)
        {
            var outputs = new ConcurrentDictionary<int, string>();
            var failed = new ConcurrentBag<string>();
            await Parallel.ForEachAsync(Enumerable.Range(0, files.Count),
                new ParallelOptions { MaxDegreeOfParallelism = Math.Max(1, parallel) },
                async (i, _) =>
                {
                    try { outputs[i] = await work(files[i]); }
                    catch (Exception e) { failed.Add($"{Path.GetFileName(files[i])}: {e.Message}"); }
                });
            if (outputs.IsEmpty && !failed.IsEmpty)
                throw new InvalidOperationException(files.Count == 1 ? failed.First().Split(": ", 2).Last() : string.Join("\n", failed.Take(3)));
            var result = ActionResult.Of(outputs.OrderBy(kv => kv.Key).Select(kv => kv.Value));
            result.Failed.AddRange(failed);
            return result;
        }

        private static WheelAction PerFile(string title, string hint, string done, Func<string, string> work, Engine engine = Engine.None) =>
            new(title, hint, done, job => Each(job.Files, f => Task.Run(() => work(f)), Environment.ProcessorCount > 4 ? 4 : 2), engine);

        private static WheelAction PerFileAsync(string title, string hint, string done, Func<string, Task<string>> work,
            Engine engine = Engine.None, int parallel = 2) =>
            new(title, hint, done, job => Each(job.Files, work, parallel), engine);

        private static WheelAction Data(string title, string hint, Func<string, string> work) => PerFile(title, hint, "Converted", work);

        // ---------- Images ----------

        private static List<WheelAction> ImageConvert(string ext, int count)
        {
            var targets = new (string Ext, string Title, string Hint)[]
            {
                ("jpg", "JPG", "photo"), ("png", "PNG", "lossless"), ("webp", "WebP", "for the web"),
                ("avif", "AVIF", "smallest"), ("tiff", "TIFF", "print"), ("gif", "GIF", "simple"),
                ("bmp", "BMP", "plain"), ("ico", "ICO", "icon"),
            };
            string same = ext switch { "jpeg" or "jfif" => "jpg", "tif" => "tiff", _ => ext };
            var list = targets.Where(t => t.Ext != same)
                .Select(t => PerFile(t.Title, t.Hint, "Converted", f => ImageOps.Convert(f, t.Ext)))
                .ToList();
            list.Add(new WheelAction("PDF", count > 1 ? $"{count} pages" : "one page", "PDF ready",
                job => Task.Run(() => ActionResult.Of(ImageOps.ToPdf(job.Files)))));
            list.Add(new WheelAction("Word", "DOCX", "Word file ready",
                job => Task.Run(() => ActionResult.Of(DataOps.ImagesToDocx(job.Files)))));
            list.Add(PerFileAsync("Text", "read the words", "Text saved", OcrOps.ImageToText, parallel: 1));
            return list;
        }

        private static List<WheelAction> ImageTools(int count)
        {
            var list = new List<WheelAction>
            {
                PerFile("Compress", "smaller file", "Compressed", ImageOps.Compress),
                PerFile("Half size", "50% width", "Resized", ImageOps.HalfSize),
                PerFile("Rotate", "90° right", "Rotated", ImageOps.RotateRight),
                PerFile("Grayscale", "black & white", "Done", ImageOps.Grayscale),
                PerFile("Clean", "no metadata", "Metadata removed", ImageOps.StripMetadata),
                new WheelAction("Read QR", "copy text", "Copied to clipboard",
                    job => Task.Run(() =>
                    {
                        var text = ImageOps.ReadQr(job.Files[0]);
                        return new ActionResult { ClipboardText = text, Message = text };
                    })),
            };
            if (count > 1) list.Add(CombinePdf());
            return list;
        }

        // ---------- PDF ----------

        private static List<WheelAction> PdfConvert(int count)
        {
            var list = new List<WheelAction>();
            if (count > 1) list.Add(new WheelAction("Merge", $"{count} PDFs", "Merged",
                job => Task.Run(() => ActionResult.Of(PdfOps.Merge(job.Files)))));
            list.Add(PerFile("Word", "editable", "Word file ready", PdfWordOps.ToWord));
            list.Add(PerFileAsync("Word exact", "same look", "Word file ready", PdfWordOps.ToWordExact));
            list.Add(PerFile("TXT", "plain text", "Text saved", PdfWordOps.ToText));
            list.Add(PerFileAsync("PNG", "every page", "Pages saved", f => PdfOps.RenderPages(f, png: true)));
            list.Add(PerFileAsync("JPG", "every page", "Pages saved", f => PdfOps.RenderPages(f, png: false)));
            list.Add(PerFileAsync("Read text", "scanned pages", "Text saved", OcrOps.PdfToText, parallel: 1));
            list.Add(PerFile("Pictures", "images inside", "Pictures saved", PdfWordOps.ExtractImages));
            return list;
        }

        private static List<WheelAction> PdfTools() => new()
        {
            PerFile("Split", "one per page", "Split", PdfOps.Split),
            PerFile("Numbers", "page numbers", "Numbers added", PdfExtras.PageNumbers),
            new WheelAction("Watermark", "faint text", "Watermark added",
                job => Each(job.Files, f => Task.Run(() => PdfExtras.Watermark(f, job.Answer ?? "")), 2),
                Ask: new Ask("Watermark text", "CONFIDENTIAL")),
            new WheelAction("Lock", "add password", "Locked",
                job => Each(job.Files, f => Task.Run(() => PdfExtras.Lock(f, job.Answer ?? "")), 2),
                Ask: new Ask("New password (at least 4 characters)", "", Password: true)),
            PerFile("Rotate", "90° right", "Rotated", PdfOps.Rotate),
            PerFile("Reverse", "page order", "Reversed", PdfOps.Reverse),
            PerFile("Optimize", "tidy & shrink", "Optimized", PdfOps.Optimize),
            PerFile("Clean", "no title/author", "Info removed", PdfOps.ClearInfo),
        };

        // ---------- Office ----------

        private static WheelAction OfficeTo(string target, string title, string hint) =>
            PerFileAsync(title, hint, "Converted", f => OfficeOps.Convert(f, target), Engine.Office, parallel: 1);

        private static List<WheelAction> OfficeConvert(string ext, string targets, bool pictures)
        {
            var labels = new Dictionary<string, (string Title, string Hint)>
            {
                ["pdf"] = ("PDF", "same look"), ["docx"] = ("DOCX", "Word"), ["odt"] = ("ODT", "OpenDocument"),
                ["rtf"] = ("RTF", "rich text"), ["txt"] = ("TXT", "plain text"), ["html"] = ("HTML", "web page"),
                ["pptx"] = ("PPTX", "PowerPoint"), ["odp"] = ("ODP", "OpenDocument"),
            };
            var list = targets.Split(' ').Where(t => t != ext).Select(t => OfficeTo(t, labels[t].Title, labels[t].Hint)).ToList();
            if (pictures)
            {
                list.Add(PerFileAsync("PNG", "every page", "Pages saved", f => OfficeOps.ToPictures(f, png: true), Engine.Office, 1));
                list.Add(PerFileAsync("JPG", "every page", "Pages saved", f => OfficeOps.ToPictures(f, png: false), Engine.Office, 1));
            }
            return list;
        }

        private static List<WheelAction> SheetConvert(string ext)
        {
            var list = new List<WheelAction> { OfficeTo("pdf", "PDF", "same look") };
            if (ext == "xlsx")
            {
                list.Add(Data("CSV", "table", f => DataOps.XlsxTo(f, "csv")));
                list.Add(Data("JSON", "data", f => DataOps.XlsxTo(f, "json")));
            }
            else
            {
                list.Add(OfficeTo("xlsx", "XLSX", "Excel"));
                list.Add(OfficeTo("csv", "CSV", "table"));
            }
            if (ext != "ods") list.Add(OfficeTo("ods", "ODS", "OpenDocument"));
            return list;
        }

        private static List<WheelAction> TextConvert(string ext)
        {
            var list = new List<WheelAction>
            {
                OfficeTo("pdf", "PDF", "A4 pages"),
                ext is "txt" or "md" or "markdown" or "log" or "ini"
                    ? PerFile("DOCX", "Word", "Word file ready", DataOps.TextToDocx)
                    : OfficeTo("docx", "DOCX", "Word"),
            };
            if (ext is "html" or "htm" or "rtf") list.Add(OfficeTo("txt", "TXT", "plain text"));
            return list;
        }

        private static List<WheelAction> SubtitleConvert(string ext)
        {
            var list = new List<WheelAction>();
            if (ext == "srt") list.Add(PerFile("VTT", "web subtitles", "Converted", DataOps.SrtToVtt));
            if (ext == "vtt") list.Add(PerFile("SRT", "subtitles", "Converted", DataOps.VttToSrt));
            list.Add(PerFile("TXT", "words only", "Text saved", DataOps.SubtitlesToText));
            return list;
        }

        private static WheelAction CombinePdf() => new("One PDF", "combine all", "Combined into one PDF",
            async job => ActionResult.Of(await OfficeOps.CombineToPdf(job.Files)));

        // ---------- Audio ----------

        private static List<WheelAction> AudioConversions(string ext)
        {
            var targets = new (string Ext, string Title, string Hint)[]
            {
                ("mp3", "MP3", "plays anywhere"), ("m4a", "M4A", "AAC"), ("wav", "WAV", "uncompressed"),
                ("flac", "FLAC", "lossless"), ("ogg", "OGG", "Vorbis"), ("opus", "Opus", "small, clear"),
            };
            return targets.Where(t => t.Ext != ext)
                .Select(t => PerFileAsync(t.Title, t.Hint, "Converted", f => MediaOps.ConvertAudio(f, t.Ext), Engine.Ffmpeg))
                .ToList();
        }

        private static List<WheelAction> AudioTools() => new()
        {
            PerFileAsync("Normalize", "even volume", "Volume fixed", MediaOps.Normalize, Engine.Ffmpeg),
            PerFileAsync("Mono", "one channel", "Done", MediaOps.Mono, Engine.Ffmpeg),
            PerFileAsync("Smaller", "64 kbps MP3", "Compressed", MediaOps.SmallMp3, Engine.Ffmpeg),
        };

        // ---------- Video ----------

        private static List<WheelAction> VideoConversions(string ext)
        {
            var list = new List<WheelAction>();
            if (ext != "mp4") list.Add(PerFileAsync("MP4", "H.264", "Converted", f => MediaOps.ConvertVideo(f, "mp4"), Engine.Ffmpeg));
            list.Add(PerFileAsync("H.265", "half the size", "Converted", MediaOps.ToHevc, Engine.Ffmpeg, parallel: 1));
            if (ext != "webm") list.Add(PerFileAsync("WebM", "VP9", "Converted", f => MediaOps.ConvertVideo(f, "webm"), Engine.Ffmpeg, parallel: 1));
            if (ext != "mov") list.Add(PerFileAsync("MOV", "H.264", "Converted", f => MediaOps.ConvertVideo(f, "mov"), Engine.Ffmpeg));
            if (ext != "mkv") list.Add(PerFileAsync("MKV", "same quality", "Converted", f => MediaOps.ConvertVideo(f, "mkv"), Engine.Ffmpeg));
            list.Add(PerFileAsync("GIF", "480 px", "GIF ready", MediaOps.ToGif, Engine.Ffmpeg));
            list.Add(PerFileAsync("MP3", "sound only", "Sound saved", f => MediaOps.ConvertAudio(f, "mp3"), Engine.Ffmpeg));
            list.Add(PerFileAsync("WAV", "sound, full", "Sound saved", f => MediaOps.ConvertAudio(f, "wav"), Engine.Ffmpeg));
            return list;
        }

        private static List<WheelAction> VideoTools() => new()
        {
            PerFileAsync("Compress", "smaller file", "Compressed", MediaOps.CompressVideo, Engine.Ffmpeg, parallel: 1),
            PerFileAsync("720p", "HD size", "Resized", MediaOps.To720p, Engine.Ffmpeg, parallel: 1),
            PerFileAsync("Mute", "remove sound", "Muted", MediaOps.Mute, Engine.Ffmpeg),
            PerFileAsync("Rotate", "90° right", "Rotated", MediaOps.RotateVideo, Engine.Ffmpeg, parallel: 1),
            PerFileAsync("Frame", "first frame", "Frame saved", MediaOps.FirstFrame, Engine.Ffmpeg),
        };

        // ---------- Archives and any file ----------

        private static List<WheelAction> ArchiveConvert(string ext)
        {
            var list = new List<WheelAction> { PerFile("Extract", "unpack here", "Extracted", ArchiveOps.Extract) };
            if (ext != "zip") list.Add(PerFile("ZIP", "repack", "Converted to ZIP", ArchiveOps.ToZip));
            return list;
        }

        private static WheelAction Zip() => new("Zip", "compress", "Zipped",
            job => Task.Run(() => ActionResult.Of(ArchiveOps.Zip(job.Files))));

        private static WheelAction TarGz() => new("tar.gz", "archive", "Archived",
            job => Task.Run(() => ActionResult.Of(ArchiveOps.TarGz(job.Files))));

        private static WheelAction Hash() => new("SHA-256", "fingerprint", "Copied to clipboard",
            job => Task.Run(() =>
            {
                var files = job.Files.Where(File.Exists).ToList(); // folders have no checksum
                if (files.Count == 0) throw new InvalidOperationException("Folders have no checksum. Drop files instead.");
                var text = string.Join(Environment.NewLine, files.Select(f =>
                    files.Count == 1 ? FileOps.Sha256(f) : $"{FileOps.Sha256(f)}  {Path.GetFileName(f)}"));
                return new ActionResult { ClipboardText = text, Message = text.Length > 80 ? text[..80] + "…" : text };
            }));

        private static WheelAction CopyPaths() => new("Copy path", "full path", "Copied to clipboard",
            job => Task.FromResult(new ActionResult
            {
                ClipboardText = string.Join(Environment.NewLine, job.Files),
                Message = job.Files.Count == 1 ? job.Files[0] : $"{job.Files.Count} paths"
            }));
    }
}
