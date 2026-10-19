using System;
using System.IO;
using System.Security.Cryptography;

namespace DragWheel.Tools
{
    public static class FileOps
    {
        /// <summary>
        /// A free path next to the source. "photo.heic" + "jpg" -> "photo.jpg", or "photo (2).jpg" if taken.
        /// </summary>
        public static string NextTo(string source, string ext, string suffix = "")
        {
            var dir = Path.GetDirectoryName(source.TrimEnd('\\', '/')) ?? Environment.CurrentDirectory;
            var name = Directory.Exists(source)
                ? Path.GetFileName(source.TrimEnd('\\', '/'))
                : Path.GetFileNameWithoutExtension(source);
            if (name.EndsWith(".tar", StringComparison.OrdinalIgnoreCase)) name = name[..^4];
            return Free(dir, name + suffix, ext);
        }

        /// <summary>A free folder path next to the source.</summary>
        public static string FolderNextTo(string source, string suffix = "")
        {
            var dir = Path.GetDirectoryName(source) ?? Environment.CurrentDirectory;
            var name = Path.GetFileNameWithoutExtension(source);
            if (name.EndsWith(".tar", StringComparison.OrdinalIgnoreCase)) name = name[..^4];
            return Free(dir, name + suffix, null);
        }

        private static string Free(string dir, string baseName, string? ext)
        {
            string Make(string n) => Path.Combine(dir, ext == null ? n : $"{n}.{ext}");
            var candidate = Make(baseName);
            for (int i = 2; File.Exists(candidate) || Directory.Exists(candidate); i++)
                candidate = Make($"{baseName} ({i})");
            return candidate;
        }

        public static string Sha256(string path)
        {
            using var stream = File.OpenRead(path);
            return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
        }
    }
}
