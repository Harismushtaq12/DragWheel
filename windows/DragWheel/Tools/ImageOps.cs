using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using ImageMagick;
using ZXing;

namespace DragWheel.Tools
{
    public static class ImageOps
    {
        private static MagickFormat FormatFor(string ext) => ext switch
        {
            "jpg" => MagickFormat.Jpeg,
            "png" => MagickFormat.Png,
            "webp" => MagickFormat.WebP,
            "avif" => MagickFormat.Avif,
            "ico" => MagickFormat.Ico,
            "bmp" => MagickFormat.Bmp,
            "gif" => MagickFormat.Gif,
            "tiff" => MagickFormat.Tiff,
            "heic" => MagickFormat.Heic,
            _ => throw new ArgumentException($"Unknown image type: {ext}")
        };

        private static bool HasNoAlpha(string ext) => ext is "jpg" or "bmp";

        /// <summary>Opens the first frame, applies phone rotation.</summary>
        private static MagickImage Load(string path)
        {
            MagickImage image;
            if (path.EndsWith(".ico", StringComparison.OrdinalIgnoreCase))
            {
                // An icon holds several sizes; take the biggest, not the first (often 16 px).
                using var frames = new MagickImageCollection(path);
                var largest = frames.OrderByDescending(f => (long)f.Width * f.Height).First();
                image = new MagickImage(largest);
            }
            else
            {
                image = new MagickImage(path);
            }
            image.AutoOrient();
            return image;
        }

        private static string Save(MagickImage image, string source, string ext, string suffix = "", uint? quality = null)
        {
            if (HasNoAlpha(ext) && image.HasAlpha)
            {
                // JPG has no transparency: put it on white instead of black.
                image.BackgroundColor = MagickColors.White;
                image.Alpha(AlphaOption.Remove);
            }
            if (ext == "ico" && (image.Width > 256 || image.Height > 256))
            {
                image.Resize(256, 256); // keeps aspect ratio
            }
            if (quality.HasValue) image.Quality = quality.Value;

            var output = FileOps.NextTo(source, ext, suffix);
            image.Write(output, FormatFor(ext));
            return output;
        }

        /// <summary>Keeps the original format where we can write it; otherwise uses JPG.</summary>
        private static string SameOrJpg(string path)
        {
            var ext = Path.GetExtension(path).TrimStart('.').ToLowerInvariant();
            return ext switch
            {
                "jpeg" or "jfif" or "jpg" => "jpg",
                "png" or "webp" or "avif" or "bmp" or "gif" or "tiff" => ext,
                "tif" => "tiff",
                _ => "jpg" // HEIC, ICO and others become JPG
            };
        }

        public static string Convert(string path, string ext)
        {
            using var image = Load(path);
            uint? quality = ext switch { "jpg" => 90, "webp" => 85, "avif" => 60, _ => null };
            return Save(image, path, ext, quality: quality);
        }

        public static string Compress(string path)
        {
            using var image = Load(path);
            var ext = SameOrJpg(path);
            image.Strip();
            if (ext == "png")
            {
                // Fewer colours is what makes a PNG smaller.
                image.Quantize(new QuantizeSettings { Colors = 256, DitherMethod = DitherMethod.FloydSteinberg });
                return Save(image, path, ext, " compressed");
            }
            return Save(image, path, ext, " compressed", quality: 70);
        }

        public static string HalfSize(string path)
        {
            using var image = Load(path);
            image.Resize(new Percentage(50));
            return Save(image, path, SameOrJpg(path), " small", quality: 88);
        }

        public static string RotateRight(string path)
        {
            using var image = Load(path);
            image.Rotate(90);
            return Save(image, path, SameOrJpg(path), " rotated", quality: 92);
        }

        public static string Grayscale(string path)
        {
            using var image = Load(path);
            image.Grayscale();
            return Save(image, path, SameOrJpg(path), " bw", quality: 92);
        }

        public static string StripMetadata(string path)
        {
            using var image = Load(path);
            image.Strip(); // removes EXIF, GPS location, camera details, comments
            return Save(image, path, SameOrJpg(path), " clean", quality: 92);
        }

        public static string ToPdf(IReadOnlyList<string> paths)
        {
            using var pages = new MagickImageCollection();
            foreach (var p in paths)
            {
                var image = Load(p);
                if (image.HasAlpha)
                {
                    image.BackgroundColor = MagickColors.White;
                    image.Alpha(AlphaOption.Remove);
                }
                // Choose the DPI so the long side is A4 height (11.69 in), not a poster.
                double dpi = Math.Max(72, Math.Max(image.Width, image.Height) / 11.69);
                image.Density = new Density(dpi, dpi, DensityUnit.PixelsPerInch);
                pages.Add(image); // the collection disposes it
            }
            var suffix = paths.Count > 1 ? $" (+{paths.Count - 1})" : "";
            var output = FileOps.NextTo(paths[0], "pdf", suffix);
            pages.Write(output, MagickFormat.Pdf);
            return output;
        }

        public static string ReadQr(string path)
        {
            using var image = Load(path);
            image.Depth = 8;
            var rgba = image.ToByteArray(MagickFormat.Rgba);
            var source = new RGBLuminanceSource(rgba, (int)image.Width, (int)image.Height,
                RGBLuminanceSource.BitmapFormat.RGBA32);

            var reader = new BarcodeReaderGeneric();
            reader.Options.TryHarder = true;
            var result = reader.Decode(source);
            if (result == null)
                throw new InvalidOperationException("No QR code or barcode found in this image.");
            return result.Text;
        }
    }
}
