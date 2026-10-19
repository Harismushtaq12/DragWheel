using System;
using System.Collections.Generic;
using System.Formats.Tar;
using System.IO;
using System.IO.Compression;
using SharpCompress.Archives;
using SharpCompress.Common;
using SharpCompress.Readers;

namespace DragWheel.Tools
{
    public static class ArchiveOps
    {
        private static string ArchiveName(IReadOnlyList<string> paths, string ext)
        {
            var suffix = paths.Count > 1 ? $" (+{paths.Count - 1})" : "";
            return FileOps.NextTo(paths[0], ext, suffix);
        }

        public static string Zip(IReadOnlyList<string> paths)
        {
            var output = ArchiveName(paths, "zip");
            using var archive = ZipFile.Open(output, ZipArchiveMode.Create);
            foreach (var p in paths)
            {
                var root = Path.GetFileName(p.TrimEnd('\\', '/'));
                if (Directory.Exists(p))
                {
                    foreach (var file in Directory.EnumerateFiles(p, "*", SearchOption.AllDirectories))
                        archive.CreateEntryFromFile(file, Entry(root, Path.GetRelativePath(p, file)), CompressionLevel.Optimal);
                    foreach (var dir in Directory.EnumerateDirectories(p, "*", SearchOption.AllDirectories))
                        if (!Directory.EnumerateFileSystemEntries(dir).GetEnumerator().MoveNext())
                            archive.CreateEntry(Entry(root, Path.GetRelativePath(p, dir)) + "/");
                }
                else
                {
                    archive.CreateEntryFromFile(p, root, CompressionLevel.Optimal);
                }
            }
            return output;
        }

        public static string TarGz(IReadOnlyList<string> paths)
        {
            var output = ArchiveName(paths, "tar.gz");
            using var file = File.Create(output);
            using var gzip = new GZipStream(file, CompressionLevel.Optimal);
            using var tar = new TarWriter(gzip, TarEntryFormat.Pax, leaveOpen: false);
            foreach (var p in paths)
            {
                var root = Path.GetFileName(p.TrimEnd('\\', '/'));
                if (Directory.Exists(p))
                {
                    foreach (var f in Directory.EnumerateFiles(p, "*", SearchOption.AllDirectories))
                        tar.WriteEntry(f, Entry(root, Path.GetRelativePath(p, f)));
                }
                else
                {
                    tar.WriteEntry(p, root);
                }
            }
            return output;
        }

        private static string Entry(string root, string relative) => (root + "/" + relative).Replace('\\', '/');

        /// <summary>True for a single gzip-compressed file that is not a tar archive, e.g. "data.csv.gz".</summary>
        public static bool IsPlainGzip(string path) =>
            path.EndsWith(".gz", StringComparison.OrdinalIgnoreCase) &&
            !path.EndsWith(".tar.gz", StringComparison.OrdinalIgnoreCase);

        public static string Extract(string path)
        {
            if (IsPlainGzip(path)) return Gunzip(path);

            var folder = FileOps.FolderNextTo(path);
            Directory.CreateDirectory(folder);
            var options = new ExtractionOptions { ExtractFullPath = true, Overwrite = true };
            var ext = Path.GetExtension(path).ToLowerInvariant();

            if (ext is ".zip" or ".7z" or ".rar")
            {
                // Extracting everything in one pass is required for "solid" RAR files
                // and much faster for 7z.
                using var archive = ArchiveFactory.Open(path);
                using var reader = archive.ExtractAllEntries();
                reader.WriteAllToDirectory(folder, options);
            }
            else
            {
                // tar, tar.gz, tgz, tar.bz2, tar.xz: read as a stream.
                using var stream = File.OpenRead(path);
                using var reader = ReaderFactory.Open(stream);
                while (reader.MoveToNextEntry())
                    if (!reader.Entry.IsDirectory) reader.WriteEntryToDirectory(folder, options);
            }
            return folder;
        }

        /// <summary>"report.csv.gz" becomes "report.csv" next to it.</summary>
        private static string Gunzip(string path)
        {
            var dir = Path.GetDirectoryName(path) ?? Environment.CurrentDirectory;
            var inner = Path.GetFileNameWithoutExtension(path);          // "report.csv"
            var innerExt = Path.GetExtension(inner).TrimStart('.');      // "csv"
            var output = FileOps.NextTo(Path.Combine(dir, inner), innerExt.Length > 0 ? innerExt : "bin");

            using var input = File.OpenRead(path);
            using var gzip = new GZipStream(input, CompressionMode.Decompress);
            using var file = File.Create(output);
            gzip.CopyTo(file);
            return output;
        }
    }
}
