package io.github.harismushtaq12.dragwheel.ops

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Xml
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser

/** Word documents and plain text. */
object DocOps {
    private val TEXT_EXT = setOf(
        "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "log", "ini", "yaml", "yml",
        "srt", "vtt", "kt", "java", "py", "js", "ts", "css", "c", "cpp", "h", "sh", "sql",
    )
    private const val MAX_CHARS = 2_000_000

    fun isDocx(f: InputFile) = f.ext == "docx" ||
        f.mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun isText(f: InputFile) = f.ext in TEXT_EXT || (f.mime.startsWith("text/") && !isDocx(f))

    fun isDocument(f: InputFile) = isDocx(f) || isText(f)

    fun readText(context: Context, f: InputFile): String =
        Storage.open(context, f).use { input ->
            if (isDocx(f)) readDocx(input)
            else input.bufferedReader().use { r ->
                val buf = CharArray(MAX_CHARS)
                val n = r.read(buf)
                if (n <= 0) "" else String(buf, 0, n)
            }
        }

    /** Reads the words of a .docx: paragraphs, tabs and line breaks. Formatting is dropped. */
    private fun readDocx(input: InputStream): String {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != "word/document.xml") continue
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(zip, "UTF-8")
                val sb = StringBuilder()
                var inText = false
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.END_DOCUMENT -> break
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "w:t" -> inText = true
                            "w:tab" -> sb.append('\t')
                            "w:br", "w:cr" -> sb.append('\n')
                        }
                        XmlPullParser.END_TAG -> when (parser.name) {
                            "w:t" -> inText = false
                            "w:p" -> sb.append('\n')
                        }
                        XmlPullParser.TEXT -> if (inText) sb.append(parser.text)
                    }
                    if (sb.length > MAX_CHARS) break
                }
                return sb.toString().trimEnd()
            }
        }
        throw IllegalStateException("This does not look like a Word (.docx) file.")
    }

    fun toTxt(context: Context, baseName: String, text: String): SavedFile =
        Storage.save(context, "$baseName.txt", "text/plain") { it.write(text.toByteArray(Charsets.UTF_8)) }

    /** A simple A4 PDF: the words with line wrapping. Fonts, tables and pictures are not kept. */
    fun toPdf(context: Context, baseName: String, text: String): SavedFile {
        val pageW = 595
        val pageH = 842
        val margin = 56
        val width = pageW - 2 * margin
        val usable = pageH - 2 * margin
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 11f
        }
        val content = text.ifEmpty { " " }
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .build()

        val pdf = PdfDocument()
        try {
            var line = 0
            var pageNo = 1
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var last = line
                while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= usable) last++
                val bottom = layout.getLineBottom(last)

                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                val c = page.canvas
                c.drawColor(Color.WHITE)
                c.save()
                c.translate(margin.toFloat(), (margin - top).toFloat())
                c.clipRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
                layout.draw(c)
                c.restore()
                pdf.finishPage(page)

                line = last + 1
                pageNo++
            }
            return Storage.save(context, "$baseName.pdf", "application/pdf") { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    /** A simple Word file: one paragraph per line. */
    fun toDocx(context: Context, baseName: String, text: String): SavedFile =
        Storage.save(context, "$baseName.docx", Storage.mimeFor("docx")) { out ->
            ZipOutputStream(out).use { zip ->
                fun put(name: String, body: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                put(
                    "[Content_Types].xml",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
                )
                put(
                    "_rels/.rels",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
                )
                val body = text.split('\n').joinToString("") { line ->
                    val runs = line.split('\t').joinToString("<w:tab/>") { part ->
                        if (part.isEmpty()) "" else "<w:t xml:space=\"preserve\">${escape(part)}</w:t>"
                    }
                    "<w:p><w:r>$runs</w:r></w:p>"
                }
                put(
                    "word/document.xml",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
                )
            }
        }

    private fun escape(s: String) = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch < ' ' -> Unit // control characters are not allowed in XML
            else -> append(ch)
        }
    }
}
