using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Text;

namespace DragWheel.Tools
{
    /// <summary>A piece of text with one look. Size in points; colour 0xRRGGBB or null.</summary>
    public sealed record DocRun(string Text, bool Bold = false, bool Italic = false, double? SizePt = null, int? Color = null);

    /// <summary>Writes a new Word (.docx) file with text, bold/italic/sizes, page breaks and pictures.</summary>
    public sealed class DocxWriter
    {
        private readonly StringBuilder _body = new();
        private readonly List<(string Name, byte[] Data)> _media = new();

        public double PageWidthPt { get; set; } = 595;
        public double PageHeightPt { get; set; } = 842;
        public double MarginPt { get; set; } = 56;

        public void Paragraph(IEnumerable<DocRun> runs, int heading = 0, double spaceAfterPt = 6, bool pageBreakBefore = false)
        {
            _body.Append("<w:p><w:pPr>");
            if (heading is >= 1 and <= 6) _body.Append($"<w:pStyle w:val=\"Heading{heading}\"/>");
            if (pageBreakBefore) _body.Append("<w:pageBreakBefore/>");
            _body.Append($"<w:spacing w:after=\"{(int)(spaceAfterPt * 20)}\"/>");
            _body.Append("</w:pPr>");
            foreach (var r in runs) Run(r);
            _body.Append("</w:p>");
        }

        public void Text(string line) => Paragraph(new[] { new DocRun(line) });

        private void Run(DocRun r)
        {
            if (r.Text.Length == 0) return;
            _body.Append("<w:r>");
            var props = new StringBuilder();
            if (r.Bold) props.Append("<w:b/>");
            if (r.Italic) props.Append("<w:i/>");
            if (r.Color is int c) props.Append($"<w:color w:val=\"{c & 0xFFFFFF:X6}\"/>");
            if (r.SizePt is double s) props.Append($"<w:sz w:val=\"{System.Math.Clamp((int)(s * 2), 2, 3276)}\"/>");
            if (props.Length > 0) _body.Append("<w:rPr>").Append(props).Append("</w:rPr>");
            var lines = r.Text.Split('\n');
            for (int i = 0; i < lines.Length; i++)
            {
                if (i > 0) _body.Append("<w:br/>");
                var pieces = lines[i].Split('\t');
                for (int j = 0; j < pieces.Length; j++)
                {
                    if (j > 0) _body.Append("<w:tab/>");
                    if (pieces[j].Length > 0) _body.Append("<w:t xml:space=\"preserve\">").Append(Escape(pieces[j])).Append("</w:t>");
                }
            }
            _body.Append("</w:r>");
        }

        /// <summary>A picture in its own paragraph. [ext] is "png" or "jpeg".</summary>
        public void Image(byte[] bytes, string ext, double widthPt, double heightPt, bool pageBreakBefore = false)
        {
            _media.Add(($"image{_media.Count + 1}.{ext}", bytes));
            int n = _media.Count;
            long cx = (long)(widthPt * 12700), cy = (long)(heightPt * 12700);
            _body.Append("<w:p><w:pPr>");
            if (pageBreakBefore) _body.Append("<w:pageBreakBefore/>");
            _body.Append("<w:spacing w:before=\"0\" w:after=\"0\"/><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>");
            _body.Append(
                $"<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"{cx}\" cy=\"{cy}\"/>" +
                $"<wp:docPr id=\"{n}\" name=\"Picture {n}\"/>" +
                "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                $"<pic:nvPicPr><pic:cNvPr id=\"{n}\" name=\"image{n}.{ext}\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                $"<pic:blipFill><a:blip r:embed=\"rIdImg{n}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                $"<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"{cx}\" cy=\"{cy}\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
                "</a:graphicData></a:graphic></wp:inline>");
            _body.Append("</w:drawing></w:r></w:p>");
        }

        public void Save(string path)
        {
            using var file = File.Create(path);
            using var zip = new ZipArchive(file, ZipArchiveMode.Create);
            void Put(string name, string text) => Put2(name, Encoding.UTF8.GetBytes(text));
            void Put2(string name, byte[] data)
            {
                var e = zip.CreateEntry(name, CompressionLevel.Optimal);
                using var s = e.Open();
                s.Write(data, 0, data.Length);
            }

            Put("[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            Put("_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            var rels = new StringBuilder();
            for (int i = 0; i < _media.Count; i++)
                rels.Append($"<Relationship Id=\"rIdImg{i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/{_media[i].Name}\"/>");
            Put("word/_rels/document.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + rels + "</Relationships>");
            foreach (var (name, data) in _media) Put2("word/media/" + name, data);

            int Tw(double pt) => (int)(pt * 20);
            Put("word/document.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"><w:body>" + _body +
                $"<w:sectPr><w:pgSz w:w=\"{Tw(PageWidthPt)}\" w:h=\"{Tw(PageHeightPt)}\"/>" +
                $"<w:pgMar w:top=\"{Tw(MarginPt)}\" w:right=\"{Tw(MarginPt)}\" w:bottom=\"{Tw(MarginPt)}\" w:left=\"{Tw(MarginPt)}\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/>" +
                "</w:sectPr></w:body></w:document>");
        }

        private static string Escape(string s)
        {
            var sb = new StringBuilder(s.Length);
            foreach (var ch in s)
            {
                switch (ch)
                {
                    case '&': sb.Append("&amp;"); break;
                    case '<': sb.Append("&lt;"); break;
                    case '>': sb.Append("&gt;"); break;
                    case '"': sb.Append("&quot;"); break;
                    default:
                        if (ch >= ' ') sb.Append(ch); // control characters are not allowed in XML
                        break;
                }
            }
            return sb.ToString();
        }
    }
}
