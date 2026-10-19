using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using DragWheel.Tools;

namespace DragWheel
{
    /// <summary>
    /// Decides what appears on the wheel for the dragged files.
    /// To add a feature, add a WheelAction to the right list. Nothing else needs to change.
    /// </summary>
    public static class Catalog
    {
        private const int MaxItems = 8;

        private static readonly HashSet<string> ImageExt = Set("jpg jpeg jfif png webp heic heif avif bmp gif tif tiff ico");
        private static readonly HashSet<string> AudioExt = Set("mp3 m4a wav flac ogg opus aac wma aif aiff");
        private static readonly HashSet<string> VideoExt = Set("mp4 mov mkv webm avi wmv m4v flv 3gp mts");
        private static readonly HashSet<string> ArchiveExt = Set("zip 7z rar tar gz tgz");

        private enum Kind { Image, Audio, Video, Pdf, Archive, Folder, Other }

        public static List<WheelAction> For(IReadOnlyList<string> files, bool tools)
        {
            var kinds = files.Select(KindOf).Distinct().ToList();
            var kind = kinds.Count == 1 ? kinds[0] : Kind.Other;
            var ext = Ext(files[0]);

            var list = kind switch
            {
                Kind.Image => tools ? ImageTools() : ImageConversions(ext, files.Count),
                Kind.Audio => tools ? AudioTools() : AudioConversions(ext),
                Kind.Video => tools ? VideoTools() : VideoConversions(ext),
                Kind.Pdf => tools ? PdfToolsList() : PdfConversions(files.Count),
                Kind.Archive => tools ? new List<WheelAction> { Hash() } : new List<WheelAction> { Extract() },
                Kind.Folder => tools ? new List<WheelAction> { Zip(), TarGz() } : new List<WheelAction> { Zip(), TarGz() },
                _ => tools ? new List<WheelAction> { Hash(), CopyPaths() } : new List<WheelAction> { Zip(), TarGz() },
            };

            // Useful everywhere: zip and checksum, if there is room.
            if (tools)
            {
                if (kind != Kind.Archive && kind != Kind.Folder && list.All(a => a.Title != "Zip")) list.Add(Zip());
                if (kind != Kind.Folder && list.All(a => a.Title != "SHA-256")) list.Add(Hash());
            }

            return list.Take(MaxItems).ToList();
        }

        private static Kind KindOf(string path)
        {
            if (Directory.Exists(path)) return Kind.Folder;
            var ext = Ext(path);
            if (ImageExt.Contains(ext)) return Kind.Image;
            if (AudioExt.Contains(ext)) return Kind.Audio;
            if (VideoExt.Contains(ext)) return Kind.Video;
            if (ext == "pdf") return Kind.Pdf;
            if (ArchiveExt.Contains(ext)) return Kind.Archive;
            if (path.EndsWith(".tar.bz2", StringComparison.OrdinalIgnoreCase) ||
                path.EndsWith(".tar.xz", StringComparison.OrdinalIgnoreCase)) return Kind.Archive;
            return Kind.Other;
        }

        private static string Ext(string path) => Path.GetExtension(path).TrimStart('.').ToLowerInvariant();

        private static HashSet<string> Set(string s) =>
            new(s.Split(' ', StringSplitOptions.RemoveEmptyEntries), StringComparer.OrdinalIgnoreCase);

        private static WheelAction PerFile(string title, string hint, string done, Func<string, string> work, bool ffmpeg = false) =>
            new(title, hint, done, files => Task.Run(() => ActionResult.Of(files.Select(work).ToList())), ffmpeg);

        private static WheelAction PerFileAsync(string title, string hint, string done, Func<string, Task<string>> work, bool ffmpeg = false) =>
            new(title, hint, done, async files =>
            {
                var outputs = new List<string>();
                foreach (var f in files) outputs.Add(await work(f));
                return ActionResult.Of(outputs);
            }, ffmpeg);

        // ---------- Images ----------

        private static List<WheelAction> ImageConversions(string ext, int count)
        {
            var targets = new (string Ext, string Title, string Hint)[]
            {
                ("jpg", "JPG", "photo"),
                ("png", "PNG", "lossless"),
                ("webp", "WebP", "for the web"),
                ("avif", "AVIF", "smallest"),
                ("ico", "ICO", "icon"),
                ("bmp", "BMP", "plain bitmap"),
            };
            string same = ext is "jpeg" or "jfif" ? "jpg" : ext;
            var list = targets
                .Where(t => t.Ext != same)
                .Select(t => PerFile(t.Title, t.Hint, "Converted", f => ImageOps.Convert(f, t.Ext)))
                .ToList();
            list.Add(new WheelAction("PDF", count > 1 ? $"{count} pages" : "one page", "PDF ready",
                files => Task.Run(() => ActionResult.Of(ImageOps.ToPdf(files)))));
            return list;
        }

        private static List<WheelAction> ImageTools() => new()
        {
            PerFile("Compress", "smaller file", "Compressed", ImageOps.Compress),
            PerFile("Half size", "50% width", "Resized", ImageOps.HalfSize),
            PerFile("Rotate", "90° right", "Rotated", ImageOps.RotateRight),
            PerFile("Grayscale", "black & white", "Done", ImageOps.Grayscale),
            PerFile("Clean", "no metadata", "Metadata removed", ImageOps.StripMetadata),
            new WheelAction("Read QR", "copy text", "Copied to clipboard",
                files => Task.Run(() =>
                {
                    var text = ImageOps.ReadQr(files[0]);
                    return new ActionResult { ClipboardText = text, Message = text };
                })),
        };

        // ---------- Audio ----------

        private static List<WheelAction> AudioConversions(string ext)
        {
            var targets = new (string Ext, string Title, string Hint)[]
            {
                ("mp3", "MP3", "plays anywhere"),
                ("m4a", "M4A", "AAC"),
                ("wav", "WAV", "uncompressed"),
                ("flac", "FLAC", "lossless"),
                ("ogg", "OGG", "Vorbis"),
                ("opus", "Opus", "small, clear"),
            };
            return targets
                .Where(t => t.Ext != ext)
                .Select(t => PerFileAsync(t.Title, t.Hint, "Converted", f => MediaOps.ConvertAudio(f, t.Ext), ffmpeg: true))
                .ToList();
        }

        private static List<WheelAction> AudioTools() => new()
        {
            PerFileAsync("Normalize", "even volume", "Volume fixed", MediaOps.Normalize, ffmpeg: true),
            PerFileAsync("Mono", "one channel", "Done", MediaOps.Mono, ffmpeg: true),
            PerFileAsync("Smaller", "64 kbps MP3", "Compressed", MediaOps.SmallMp3, ffmpeg: true),
        };

        // ---------- Video ----------

        private static List<WheelAction> VideoConversions(string ext)
        {
            var list = new List<WheelAction>();
            if (ext != "mp4") list.Add(PerFileAsync("MP4", "H.264", "Converted", f => MediaOps.ConvertVideo(f, "mp4"), true));
            if (ext != "webm") list.Add(PerFileAsync("WebM", "VP9", "Converted", f => MediaOps.ConvertVideo(f, "webm"), true));
            if (ext != "mov") list.Add(PerFileAsync("MOV", "H.264", "Converted", f => MediaOps.ConvertVideo(f, "mov"), true));
            if (ext != "mkv") list.Add(PerFileAsync("MKV", "same quality", "Converted", f => MediaOps.ConvertVideo(f, "mkv"), true));
            list.Add(PerFileAsync("GIF", "480 px", "GIF ready", MediaOps.ToGif, true));
            list.Add(PerFileAsync("MP3", "audio only", "Audio saved", f => MediaOps.ConvertAudio(f, "mp3"), true));
            return list;
        }

        private static List<WheelAction> VideoTools() => new()
        {
            PerFileAsync("Compress", "smaller file", "Compressed", MediaOps.CompressVideo, true),
            PerFileAsync("720p", "HD size", "Resized", MediaOps.To720p, true),
            PerFileAsync("Mute", "remove sound", "Muted", MediaOps.Mute, true),
            PerFileAsync("Rotate", "90° right", "Rotated", MediaOps.RotateVideo, true),
            PerFileAsync("Frame", "first frame", "Frame saved", MediaOps.FirstFrame, true),
        };

        // ---------- PDF ----------

        private static List<WheelAction> PdfConversions(int count)
        {
            var list = new List<WheelAction>
            {
                PerFileAsync("PNG", "every page", "Pages saved", f => PdfOps.RenderPages(f, png: true)),
                PerFileAsync("JPG", "every page", "Pages saved", f => PdfOps.RenderPages(f, png: false)),
            };
            if (count > 1)
                list.Insert(0, new WheelAction("Merge", $"{count} PDFs", "Merged",
                    files => Task.Run(() => ActionResult.Of(PdfOps.Merge(files)))));
            return list;
        }

        private static List<WheelAction> PdfToolsList() => new()
        {
            PerFile("Split", "one per page", "Split", PdfOps.Split),
            PerFile("Reverse", "page order", "Reversed", PdfOps.Reverse),
            PerFile("Rotate", "90° right", "Rotated", PdfOps.Rotate),
            PerFile("Optimize", "tidy & shrink", "Optimized", PdfOps.Optimize),
            PerFile("Clean", "no title/author", "Info removed", PdfOps.ClearInfo),
        };

        // ---------- Archives and any file ----------

        private static WheelAction Zip() => new("Zip", "compress", "Zipped",
            files => Task.Run(() => ActionResult.Of(ArchiveOps.Zip(files))));

        private static WheelAction TarGz() => new("tar.gz", "archive", "Archived",
            files => Task.Run(() => ActionResult.Of(ArchiveOps.TarGz(files))));

        private static WheelAction Extract() => PerFile("Extract", "unpack here", "Extracted", ArchiveOps.Extract);

        private static WheelAction Hash() => new("SHA-256", "copy checksum", "Copied to clipboard",
            all => Task.Run(() =>
            {
                var files = all.Where(File.Exists).ToList(); // folders have no checksum
                if (files.Count == 0) throw new InvalidOperationException("Folders have no checksum. Drop files instead.");
                var text = string.Join(Environment.NewLine, files.Select(f =>
                    files.Count == 1 ? FileOps.Sha256(f) : $"{FileOps.Sha256(f)}  {Path.GetFileName(f)}"));
                return new ActionResult { ClipboardText = text, Message = text.Length > 80 ? text[..80] + "…" : text };
            }));

        private static WheelAction CopyPaths() => new("Copy path", "full path", "Copied to clipboard",
            files => Task.FromResult(new ActionResult
            {
                ClipboardText = string.Join(Environment.NewLine, files),
                Message = files.Count == 1 ? files[0] : $"{files.Count} paths"
            }));
    }
}
