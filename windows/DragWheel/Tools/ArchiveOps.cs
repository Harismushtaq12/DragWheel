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

        public static string Extract(string path)
        {
            var folder = FileOps.FolderNextTo(path);
            Directory.CreateDirectory(folder);
            var options = new ExtractionOptions { ExtractFullPath = true, Overwrite = true };
            var ext = Path.GetExtension(path).ToLowerInvariant();

            if (ext is ".zip" or ".7z" or ".rar")
            {
                using var archive = ArchiveFactory.Open(path);
                foreach (var entry in archive.Entries)
                    if (!entry.IsDirectory) entry.WriteToDirectory(folder, options);
            }
            else
            {
                // tar, tar.gz, tgz, gz, bz2, xz: read as a stream.
                using var stream = File.OpenRead(path);
                using var reader = ReaderFactory.Open(stream);
                while (reader.MoveToNextEntry())
                    if (!reader.Entry.IsDirectory) reader.WriteEntryToDirectory(folder, options);
            }
            return folder;
        }
    }
}
