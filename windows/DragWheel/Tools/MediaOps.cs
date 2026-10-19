using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;

namespace DragWheel.Tools
{
    /// <summary>Audio and video, done by ffmpeg.</summary>
    public static class MediaOps
    {
        private static async Task<string> Run(string input, string ext, string suffix, params string[] args)
        {
            var output = FileOps.NextTo(input, ext, suffix);
            await Ffmpeg.Run(input, output, args);
            return output;
        }

        private static string ExtOf(string path) => Path.GetExtension(path).TrimStart('.').ToLowerInvariant();

        public static Task<string> ConvertAudio(string input, string ext) => ext switch
        {
            "mp3" => Run(input, ext, "", "-vn", "-c:a", "libmp3lame", "-q:a", "2"),
            "m4a" => Run(input, ext, "", "-vn", "-c:a", "aac", "-b:a", "192k"),
            "wav" => Run(input, ext, "", "-vn", "-c:a", "pcm_s16le"),
            "flac" => Run(input, ext, "", "-vn", "-c:a", "flac"),
            "ogg" => Run(input, ext, "", "-vn", "-c:a", "libvorbis", "-q:a", "5"),
            "opus" => Run(input, ext, "", "-vn", "-c:a", "libopus", "-b:a", "128k"),
            _ => Run(input, ext, "", "-vn"),
        };

        public static Task<string> Normalize(string input) =>
            Run(input, AudioOutExt(input), " normalized", "-vn", "-af", "loudnorm=I=-16:TP=-1.5:LRA=11");

        public static Task<string> Mono(string input) =>
            Run(input, AudioOutExt(input), " mono", "-vn", "-ac", "1");

        public static Task<string> SmallMp3(string input) =>
            Run(input, "mp3", " small", "-vn", "-c:a", "libmp3lame", "-b:a", "64k");

        /// <summary>Same type as the input, except a few that ffmpeg cannot write well.</summary>
        private static string AudioOutExt(string input)
        {
            var ext = ExtOf(input);
            return ext is "wma" or "aac" or "aif" or "aiff" ? "m4a" : ext;
        }

        private static readonly string[] H264 =
            { "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k" };

        public static Task<string> ConvertVideo(string input, string ext) => ext switch
        {
            "mp4" => Run(input, ext, "", Concat(H264, "-movflags", "+faststart")),
            "mov" => Run(input, ext, "", H264),
            "webm" => Run(input, ext, "", "-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0", "-row-mt", "1", "-c:a", "libopus", "-b:a", "128k"),
            "mkv" => Run(input, ext, "", "-map", "0", "-c", "copy"), // just a new container, no quality loss
            _ => Run(input, ext, ""),
        };

        public static Task<string> ToGif(string input) =>
            Run(input, "gif", "",
                "-vf", "fps=12,scale=480:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer",
                "-loop", "0");

        public static Task<string> CompressVideo(string input) =>
            Run(input, "mp4", " compressed",
                "-c:v", "libx264", "-preset", "slow", "-crf", "28", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart");

        public static Task<string> To720p(string input) =>
            Run(input, "mp4", " 720p", Concat(new[] { "-vf", "scale=-2:'min(720,ih)'" }, H264));

        public static Task<string> Mute(string input) =>
            Run(input, VideoOutExt(input), " muted", "-c:v", "copy", "-an");

        public static Task<string> RotateVideo(string input) =>
            Run(input, "mp4", " rotated", Concat(new[] { "-vf", "transpose=1" }, H264));

        public static Task<string> FirstFrame(string input) =>
            Run(input, "png", " frame", "-frames:v", "1");

        private static string VideoOutExt(string input)
        {
            var ext = ExtOf(input);
            return ext is "mp4" or "mov" or "mkv" or "webm" or "m4v" ? ext : "mkv";
        }

        private static string[] Concat(string[] a, params string[] b)
        {
            var list = new List<string>(a);
            list.AddRange(b);
            return list.ToArray();
        }
    }
}
