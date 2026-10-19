using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using ClosedXML.Excel;

namespace DragWheel.Tools
{
    /// <summary>Spreadsheets as data (Excel, CSV, JSON) and subtitles (SRT, VTT). No extra programs needed.</summary>
    public static class DataOps
    {
        // ---------- CSV ----------

        public static List<List<string>> ReadCsv(string path)
        {
            var text = File.ReadAllText(path);
            char sep = GuessSeparator(text);
            var rows = new List<List<string>>();
            var row = new List<string>();
            var cell = new StringBuilder();
            bool quoted = false;
            for (int i = 0; i < text.Length; i++)
            {
                char c = text[i];
                if (quoted)
                {
                    if (c == '"' && i + 1 < text.Length && text[i + 1] == '"') { cell.Append('"'); i++; }
                    else if (c == '"') quoted = false;
                    else cell.Append(c);
                }
                else if (c == '"') quoted = true;
                else if (c == sep) { row.Add(cell.ToString()); cell.Clear(); }
                else if (c == '\n' || c == '\r')
                {
                    if (c == '\r' && i + 1 < text.Length && text[i + 1] == '\n') i++;
                    row.Add(cell.ToString()); cell.Clear();
                    rows.Add(row); row = new List<string>();
                }
                else cell.Append(c);
            }
            if (cell.Length > 0 || row.Count > 0) { row.Add(cell.ToString()); rows.Add(row); }
            return rows;
        }

        private static char GuessSeparator(string text)
        {
            var firstLine = text.Split('\n')[0];
            var options = new[] { ',', ';', '\t', '|' };
            return options.OrderByDescending(c => firstLine.Count(ch => ch == c)).First();
        }

        private static string Csv(IEnumerable<IEnumerable<string>> rows)
        {
            var sb = new StringBuilder();
            foreach (var r in rows)
            {
                sb.AppendLine(string.Join(",", r.Select(v =>
                    v.IndexOfAny(new[] { ',', '"', '\n', '\r' }) >= 0 ? "\"" + v.Replace("\"", "\"\"") + "\"" : v)));
            }
            return sb.ToString();
        }

        // ---------- Excel ----------

        private static List<(string Name, List<List<string>> Rows)> ReadXlsx(string path)
        {
            using var wb = new XLWorkbook(path);
            var sheets = new List<(string, List<List<string>>)>();
            foreach (var ws in wb.Worksheets)
            {
                var used = ws.RangeUsed();
                var rows = new List<List<string>>();
                if (used != null)
                {
                    foreach (var r in used.Rows())
                        rows.Add(r.Cells().Select(c => c.GetFormattedString()).ToList());
                }
                sheets.Add((ws.Name, rows));
            }
            return sheets;
        }

        private static void WriteXlsx(List<List<string>> rows, string output)
        {
            using var wb = new XLWorkbook();
            var ws = wb.AddWorksheet("Sheet1");
            for (int r = 0; r < rows.Count; r++)
            {
                for (int c = 0; c < rows[r].Count; c++)
                {
                    var v = rows[r][c];
                    var cell = ws.Cell(r + 1, c + 1);
                    if (r > 0 && double.TryParse(v, NumberStyles.Float, CultureInfo.InvariantCulture, out var d)) cell.Value = d;
                    else cell.Value = v;
                }
            }
            if (rows.Count > 0)
            {
                ws.Row(1).Style.Font.Bold = true;
                ws.SheetView.FreezeRows(1);
            }
            ws.Columns().AdjustToContents();
            wb.SaveAs(output);
        }

        public static string XlsxTo(string path, string ext)
        {
            var sheets = ReadXlsx(path).Where(s => s.Rows.Count > 0).ToList();
            if (sheets.Count == 0) throw new InvalidOperationException("This workbook is empty.");
            if (sheets.Count == 1) return WriteRows(sheets[0].Rows, FileOps.NextTo(path, ext), ext);
            // One file per sheet, in a folder.
            var folder = FileOps.FolderNextTo(path, " sheets");
            Directory.CreateDirectory(folder);
            foreach (var (name, rows) in sheets)
            {
                var safe = string.Concat(name.Select(ch => Path.GetInvalidFileNameChars().Contains(ch) ? '_' : ch));
                WriteRows(rows, Path.Combine(folder, safe + "." + ext), ext);
            }
            return folder;
        }

        public static string CsvTo(string path, string ext) => WriteRows(ReadCsv(path), FileOps.NextTo(path, ext), ext);

        public static string JsonTo(string path, string ext) => WriteRows(ReadJsonTable(path), FileOps.NextTo(path, ext), ext);

        private static string WriteRows(List<List<string>> rows, string output, string ext)
        {
            switch (ext)
            {
                case "csv": File.WriteAllText(output, Csv(rows), new UTF8Encoding(true)); break;
                case "json": File.WriteAllText(output, ToJson(rows), new UTF8Encoding(false)); break;
                case "xlsx": WriteXlsx(rows, output); break;
                default: throw new InvalidOperationException("Unknown type: " + ext);
            }
            return output;
        }

        // ---------- JSON ----------

        /// <summary>First row is the header; each other row becomes an object.</summary>
        private static string ToJson(List<List<string>> rows)
        {
            if (rows.Count == 0) return "[]";
            var header = rows[0].Select((h, i) => string.IsNullOrWhiteSpace(h) ? $"column{i + 1}" : h.Trim()).ToList();
            var array = new JsonArray();
            foreach (var r in rows.Skip(1))
            {
                var obj = new JsonObject();
                for (int i = 0; i < header.Count; i++)
                {
                    var v = i < r.Count ? r[i] : "";
                    if (double.TryParse(v, NumberStyles.Float, CultureInfo.InvariantCulture, out var d) && v.Trim().Length > 0) obj[header[i]] = d;
                    else if (v is "true" or "false") obj[header[i]] = v == "true";
                    else obj[header[i]] = v;
                }
                array.Add(obj);
            }
            return array.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
        }

        /// <summary>A JSON list of objects as a table (keys become the header).</summary>
        private static List<List<string>> ReadJsonTable(string path)
        {
            var node = JsonNode.Parse(File.ReadAllText(path));
            var items = node as JsonArray
                ?? (node as JsonObject)?.Select(kv => kv.Value).OfType<JsonArray>().FirstOrDefault()
                ?? throw new InvalidOperationException("This JSON is not a list of items, so it cannot become a table.");
            var keys = new List<string>();
            foreach (var item in items.OfType<JsonObject>())
                foreach (var kv in item)
                    if (!keys.Contains(kv.Key)) keys.Add(kv.Key);
            if (keys.Count == 0) throw new InvalidOperationException("This JSON list has no named fields.");
            var rows = new List<List<string>> { keys };
            foreach (var item in items.OfType<JsonObject>())
                rows.Add(keys.Select(k => item[k] switch
                {
                    null => "",
                    JsonValue v => v.ToString(),
                    var other => other.ToJsonString(),
                }).ToList());
            return rows;
        }

        // ---------- Subtitles ----------

        private static readonly Regex SrtTime = new(@"(\d{2}:\d{2}:\d{2}),(\d{3})");
        private static readonly Regex VttTime = new(@"(\d{2}:\d{2}:\d{2})\.(\d{3})");

        public static string SrtToVtt(string path)
        {
            var text = File.ReadAllText(path).Replace("\r\n", "\n");
            var output = FileOps.NextTo(path, "vtt");
            File.WriteAllText(output, "WEBVTT\n\n" + SrtTime.Replace(text, "$1.$2"), new UTF8Encoding(false));
            return output;
        }

        public static string VttToSrt(string path)
        {
            var lines = File.ReadAllText(path).Replace("\r\n", "\n").Split('\n').ToList();
            if (lines.Count > 0 && lines[0].StartsWith("WEBVTT")) lines.RemoveAt(0);
            var blocks = string.Join("\n", lines).Split("\n\n", StringSplitOptions.RemoveEmptyEntries)
                .Where(b => b.Contains("-->")).ToList();
            var sb = new StringBuilder();
            for (int i = 0; i < blocks.Count; i++)
            {
                var b = blocks[i].Trim('\n');
                var parts = b.Split('\n').ToList();
                int t = parts.FindIndex(p => p.Contains("-->"));
                var timing = parts[t];
                // VTT may drop the hours: 01:02.000 -> 00:01:02,000
                timing = Regex.Replace(timing, @"(?<!\d:)\b(\d{2}:\d{2}\.\d{3})", "00:$1");
                timing = VttTime.Replace(timing, "$1,$2");
                timing = Regex.Replace(timing, @"(\d{2}:\d{2}:\d{2},\d{3} --> \d{2}:\d{2}:\d{2},\d{3}).*", "$1");
                sb.Append(i + 1).Append('\n').Append(timing).Append('\n');
                sb.Append(string.Join("\n", parts.Skip(t + 1))).Append("\n\n");
            }
            var output = FileOps.NextTo(path, "srt");
            File.WriteAllText(output, sb.ToString(), new UTF8Encoding(false));
            return output;
        }

        /// <summary>Only the spoken words, without numbers and times.</summary>
        public static string SubtitlesToText(string path)
        {
            var words = File.ReadAllText(path).Replace("\r\n", "\n").Split('\n')
                .Where(l => l.Length > 0 && !l.Contains("-->") && !l.StartsWith("WEBVTT") && !int.TryParse(l.Trim(), out _))
                .Select(l => Regex.Replace(l, "<[^>]+>", ""));
            var output = FileOps.NextTo(path, "txt");
            File.WriteAllText(output, string.Join("\n", words), new UTF8Encoding(true));
            return output;
        }

        // ---------- Text to Word ----------

        public static string TextToDocx(string path)
        {
            var w = new DocxWriter();
            foreach (var line in File.ReadAllLines(path)) w.Text(line);
            var output = FileOps.NextTo(path, "docx");
            w.Save(output);
            return output;
        }

        public static string ImagesToDocx(IReadOnlyList<string> files)
        {
            var w = new DocxWriter();
            double maxW = w.PageWidthPt - 2 * w.MarginPt, maxH = w.PageHeightPt - 2 * w.MarginPt - 20;
            foreach (var f in files)
            {
                using var img = new ImageMagick.MagickImage(f);
                img.AutoOrient();
                if (img.Width > 2400 || img.Height > 2400) img.Resize(new ImageMagick.MagickGeometry(2400, 2400));
                bool png = img.HasAlpha;
                var bytes = img.ToByteArray(png ? ImageMagick.MagickFormat.Png : ImageMagick.MagickFormat.Jpeg);
                double wPt = img.Width * 0.75, hPt = img.Height * 0.75;
                double s = Math.Min(1, Math.Min(maxW / wPt, maxH / hPt));
                w.Image(bytes, png ? "png" : "jpeg", wPt * s, hPt * s);
            }
            var output = FileOps.NextTo(files[0], "docx", files.Count > 1 ? $" (+{files.Count - 1})" : "");
            w.Save(output);
            return output;
        }
    }
}
