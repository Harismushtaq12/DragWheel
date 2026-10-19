package io.github.harismushtaq12.dragwheel.ops.doc

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a new Word (.docx) file with text, bold/italic/sizes, page breaks and pictures. */
class DocxWriter {
    private val body = StringBuilder()
    private val media = mutableListOf<Pair<String, ByteArray>>()

    var pageWidthPt = 595f
    var pageHeightPt = 842f
    var marginPt = 56f

    fun paragraph(runs: List<Run>, align: Int = 0, heading: Int = 0, spaceAfterPt: Float = 6f) {
        body.append("<w:p><w:pPr>")
        if (heading in 1..6) body.append("<w:pStyle w:val=\"Heading$heading\"/>")
        body.append("<w:spacing w:after=\"${(spaceAfterPt * 20).toInt()}\"/>")
        when (align) {
            1 -> body.append("<w:jc w:val=\"center\"/>")
            2 -> body.append("<w:jc w:val=\"right\"/>")
            3 -> body.append("<w:jc w:val=\"both\"/>")
        }
        body.append("</w:pPr>")
        for (r in runs) run(r)
        body.append("</w:p>")
    }

    fun text(line: String) = paragraph(listOf(Run(line)))

    fun pageBreak() {
        body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
    }

    private fun run(r: Run) {
        if (r.text.isEmpty()) return
        body.append("<w:r>")
        val props = buildString {
            if (r.bold) append("<w:b/>")
            if (r.italic) append("<w:i/>")
            if (r.underline) append("<w:u w:val=\"single\"/>")
            r.color?.let { append("<w:color w:val=\"%06X\"/>".format(it and 0xFFFFFF)) }
            r.sizePt?.let { append("<w:sz w:val=\"${(it * 2).toInt().coerceIn(2, 3276)}\"/>") }
        }
        if (props.isNotEmpty()) body.append("<w:rPr>").append(props).append("</w:rPr>")
        val parts = r.text.split('\n')
        parts.forEachIndexed { i, line ->
            if (i > 0) body.append("<w:br/>")
            line.split('\t').forEachIndexed { j, piece ->
                if (j > 0) body.append("<w:tab/>")
                if (piece.isNotEmpty()) body.append("<w:t xml:space=\"preserve\">").append(escape(piece)).append("</w:t>")
            }
        }
        body.append("</w:r>")
    }

    /** A picture in its own paragraph. [ext] is "png" or "jpeg". */
    fun image(bytes: ByteArray, ext: String, widthPt: Float, heightPt: Float, align: Int = 1) {
        media += "image${media.size + 1}.$ext" to bytes
        val n = media.size
        val cx = (widthPt * 12700).toLong()
        val cy = (heightPt * 12700).toLong()
        body.append("<w:p><w:pPr><w:spacing w:after=\"0\"/>")
        if (align == 1) body.append("<w:jc w:val=\"center\"/>")
        body.append("</w:pPr><w:r><w:drawing>")
        body.append(
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
                "<wp:docPr id=\"$n\" name=\"Picture $n\"/>" +
                "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"image$n.$ext\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                "<pic:blipFill><a:blip r:embed=\"rIdImg$n\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
                "</a:graphicData></a:graphic></wp:inline>"
        )
        body.append("</w:drawing></w:r></w:p>")
    }

    fun write(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            fun put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))

            put(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
            )
            put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
            )
            val rels = media.indices.joinToString("") { i ->
                "<Relationship Id=\"rIdImg${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/${media[i].first}\"/>"
            }
            put(
                "word/_rels/document.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>"""
            )
            for ((name, data) in media) put("word/media/$name", data)

            val tw = { pt: Float -> (pt * 20).toInt() }
            put(
                "word/document.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>$body<w:sectPr><w:pgSz w:w="${tw(pageWidthPt)}" w:h="${tw(pageHeightPt)}"/><w:pgMar w:top="${tw(marginPt)}" w:right="${tw(marginPt)}" w:bottom="${tw(marginPt)}" w:left="${tw(marginPt)}" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr></w:body></w:document>"""
            )
        }
    }

    companion object {
        fun escape(s: String) = buildString {
            for (ch in s) when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch < ' ' -> Unit // not allowed in XML
                else -> append(ch)
            }
        }
    }
}
