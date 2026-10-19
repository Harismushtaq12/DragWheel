using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using DragWheel.Engines;

namespace DragWheel.Tools
{
    /// <summary>Word, Excel and PowerPoint files. Uses Microsoft Office when installed, otherwise LibreOffice.</summary>
    public static class OfficeOps
    {
        public enum Family { Word, Sheet, Slides }

        public static Family? FamilyOf(string path) => Path.GetExtension(path).TrimStart('.').ToLowerInvariant() switch
        {
            "doc" or "docx" or "docm" or "dot" or "dotx" or "odt" or "rtf" or "wpd" => Family.Word,
            "xls" or "xlsx" or "xlsm" or "xlsb" or "ods" => Family.Sheet,
            "ppt" or "pptx" or "pptm" or "pps" or "ppsx" or "odp" => Family.Slides,
            _ => null,
        };

        /// <summary>True if something on this PC can convert this family.</summary>
        public static bool CanConvert(Family f) => LibreOffice.Find() != null || f switch
        {
            Family.Word => Office.HasWord,
            Family.Sheet => Office.HasExcel,
            _ => Office.HasPowerPoint,
        };

        /// <summary>Converts to [ext] next to the original. ODF formats always go through LibreOffice.</summary>
        public static async Task<string> Convert(string path, string ext)
        {
            var output = FileOps.NextTo(path, ext);
            await ConvertTo(path, ext, output);
            return output;
        }

        public static async Task ConvertTo(string path, string ext, string output)
        {
            var family = FamilyOf(path) ?? (ext is "pdf" or "docx" or "odt" or "rtf" ? Family.Word : Family.Word);
            var inputExt = Path.GetExtension(path).TrimStart('.').ToLowerInvariant();
            bool odf = ext is "odt" or "ods" or "odp" || inputExt is "odt" or "ods" or "odp";

            if (!odf)
            {
                switch (family)
                {
                    case Family.Word when Office.HasWord:
                        int? word = ext switch { "pdf" => 17, "docx" => 16, "rtf" => 6, "txt" => 2, "html" => 10, "doc" => 0, _ => null };
                        if (word != null) { await Office.Word(path, output, word.Value); return; }
                        break;
                    case Family.Sheet when Office.HasExcel:
                        int? excel = ext switch { "pdf" => -1, "xlsx" => 51, "csv" => 62, "xls" => 56, _ => null };
                        if (excel != null) { await Office.Excel(path, output, excel.Value); return; }
                        break;
                    case Family.Slides when Office.HasPowerPoint:
                        int? ppt = ext switch { "pdf" => 32, "pptx" => 24, "ppt" => 1, _ => null };
                        if (ppt != null) { await Office.PowerPoint(path, output, ppt.Value); return; }
                        break;
                }
            }

            var spec = ext switch
            {
                "docx" => "docx:MS Word 2007 XML",
                "doc" => "doc:MS Word 97",
                "xlsx" => "xlsx:Calc MS Excel 2007 XML",
                "xls" => "xls:MS Excel 97",
                "pptx" => "pptx:Impress MS PowerPoint 2007 XML",
                "ppt" => "ppt:MS PowerPoint 97",
                "txt" => "txt:Text (encoded):UTF8",
                "csv" => "csv:Text - txt - csv (StarCalc):44,34,76,1",
                "html" => family == Family.Word ? "html:XHTML Writer File:UTF8" : "html",
                _ => ext,
            };
            await LibreOffice.Convert(path, spec, output);
        }

        /// <summary>Makes a PDF in a temporary folder (used for pictures of pages and for combining).</summary>
        public static async Task<string> ToTempPdf(string path)
        {
            var dir = Path.Combine(Path.GetTempPath(), "dragwheel-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(dir);
            var output = Path.Combine(dir, Path.GetFileNameWithoutExtension(path) + ".pdf");
            await ConvertTo(path, "pdf", output);
            return output;
        }

        /// <summary>Every page or slide as a picture, in a folder next to the original.</summary>
        public static async Task<string> ToPictures(string path, bool png)
        {
            var pdf = await ToTempPdf(path);
            try
            {
                return await PdfOps.RenderPages(pdf, png, FileOps.FolderNextTo(path, png ? " PNG" : " JPG"));
            }
            finally
            {
                try { Directory.Delete(Path.GetDirectoryName(pdf)!, true); } catch { }
            }
        }

        /// <summary>Any mix of PDFs, pictures and Office files, in order, as one PDF.</summary>
        public static async Task<string> CombineToPdf(IReadOnlyList<string> files)
        {
            var parts = new List<string>();
            var temps = new List<string>();
            try
            {
                foreach (var f in files)
                {
                    var ext = Path.GetExtension(f).TrimStart('.').ToLowerInvariant();
                    if (ext == "pdf") parts.Add(f);
                    else if (FamilyOf(f) != null)
                    {
                        var p = await ToTempPdf(f);
                        temps.Add(Path.GetDirectoryName(p)!);
                        parts.Add(p);
                    }
                    else
                    {
                        var dir = Path.Combine(Path.GetTempPath(), "dragwheel-" + Guid.NewGuid().ToString("N"));
                        Directory.CreateDirectory(dir);
                        temps.Add(dir);
                        var p = Path.Combine(dir, "picture.pdf");
                        ImageOps.ToPdfAt(new[] { f }, p);
                        parts.Add(p);
                    }
                }
                var output = FileOps.NextTo(files[0], "pdf", files.Count > 1 ? " combined" : "");
                PdfOps.MergeTo(parts, output);
                return output;
            }
            finally
            {
                foreach (var d in temps) try { Directory.Delete(d, true); } catch { }
            }
        }
    }
}
