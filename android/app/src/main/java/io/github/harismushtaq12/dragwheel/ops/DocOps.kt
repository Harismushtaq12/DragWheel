package io.github.harismushtaq12.dragwheel.ops

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.util.Base64
import android.util.Xml
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import io.github.harismushtaq12.dragwheel.ops.doc.DocLayout
import io.github.harismushtaq12.dragwheel.ops.doc.DocxDoc
import io.github.harismushtaq12.dragwheel.ops.doc.DocxEditor
import io.github.harismushtaq12.dragwheel.ops.doc.DocxReader
import io.github.harismushtaq12.dragwheel.ops.doc.DocxWriter
import io.github.harismushtaq12.dragwheel.ops.doc.PageBreak
import io.github.harismushtaq12.dragwheel.ops.doc.Para
import io.github.harismushtaq12.dragwheel.ops.doc.Picture
import io.github.harismushtaq12.dragwheel.ops.doc.Table
import java.io.InputStream
import java.util.zip.ZipInputStream
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

    // ---------- Reading ----------

    fun readText(context: Context, f: InputFile): String =
        Storage.open(context, f).use { input ->
            if (isDocx(f)) readDocxText(input)
            else input.bufferedReader().use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < MAX_CHARS) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
        }

    /** The document with its formatting, for preview and conversion. */
    fun model(context: Context, f: InputFile): DocxDoc =
        if (isDocx(f)) Storage.open(context, f).use { DocxReader.read(it) }
        else DocLayout.fromText(readText(context, f))

    /** Just the words of a .docx: paragraphs, tabs and line breaks. */
    private fun readDocxText(input: InputStream): String {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != "word/document.xml") continue
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(zip, "UTF-8")
                val sb = StringBuilder()
                var inText = false
                var inTabStops = false
                var fallback = 0
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.END_DOCUMENT -> break
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "mc:Fallback" -> fallback++
                            "w:tabs" -> inTabStops = true
                            "w:t" -> inText = fallback == 0
                            "w:tab" -> if (!inTabStops && fallback == 0) sb.append('\t')
                            "w:br", "w:cr" -> if (fallback == 0) sb.append('\n')
                        }
                        XmlPullParser.END_TAG -> when (parser.name) {
                            "mc:Fallback" -> fallback = (fallback - 1).coerceAtLeast(0)
                            "w:tabs" -> inTabStops = false
                            "w:t" -> inText = false
                            "w:p" -> if (fallback == 0) sb.append('\n')
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

    // ---------- Making files ----------

    fun toTxt(context: Context, baseName: String, text: String): SavedFile =
        Storage.save(context, "$baseName.txt", "text/plain") { it.write(text.toByteArray(Charsets.UTF_8)) }

    /** A PDF that keeps the document's look: bold, italic, sizes, colours, headings, tables, pictures. */
    fun toPdf(context: Context, baseName: String, doc: DocxDoc): SavedFile {
        val layout = DocLayout(doc)
        val pdf = PdfDocument()
        try {
            for (i in 0 until layout.pageCount) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(layout.pageW.toInt(), layout.pageH.toInt(), i + 1).create())
                layout.draw(page.canvas, i)
                pdf.finishPage(page)
            }
            return Storage.save(context, "$baseName.pdf", "application/pdf") { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    fun toPdf(context: Context, baseName: String, text: String): SavedFile = toPdf(context, baseName, DocLayout.fromText(text))

    /** Each page as a picture. */
    fun toImages(context: Context, baseName: String, doc: DocxDoc, png: Boolean): List<SavedFile> {
        val layout = DocLayout(doc)
        val ext = if (png) "png" else "jpg"
        val digits = layout.pageCount.toString().length
        return (0 until layout.pageCount).map { i ->
            val bitmap = layout.render(i, 1240) // about 150 dpi on A4
            Storage.save(context, "page ${(i + 1).toString().padStart(digits, '0')}.$ext", subfolder = "$baseName pages") { out ->
                bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
            }.also { bitmap.recycle() }
        }
    }

    /** A new Word file from plain text: one paragraph per line. */
    fun toDocx(context: Context, baseName: String, text: String): SavedFile {
        val w = DocxWriter()
        for (line in text.split('\n')) w.text(line.trimEnd('\r'))
        return Storage.save(context, "$baseName.docx", Storage.mimeFor("docx")) { w.write(it) }
    }

    /** A web page that keeps headings, bold, italic, colours, tables and pictures. */
    fun toHtml(context: Context, baseName: String, doc: DocxDoc): SavedFile {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">")
        sb.append("<title>").append(esc(baseName)).append("</title>")
        sb.append("<style>body{font-family:sans-serif;max-width:46em;margin:2em auto;padding:0 1em;line-height:1.5}")
        sb.append("table{border-collapse:collapse}td{border:1px solid #999;padding:4px 8px;vertical-align:top}img{max-width:100%}</style>")
        sb.append("</head><body>")
        fun para(p: Para, tag: String? = null) {
            val t = tag ?: when (p.heading) { 9 -> "h1"; in 1..6 -> "h${(p.heading + 1).coerceAtMost(6)}"; else -> "p" }
            val align = when (p.align) { 1 -> " style=\"text-align:center\""; 2 -> " style=\"text-align:right\""; 3 -> " style=\"text-align:justify\""; else -> "" }
            sb.append("<$t$align>")
            p.bullet?.let { sb.append("&bull; ") }
            for (r in p.runs) {
                var s = esc(r.text).replace("\n", "<br>").replace("\t", "&emsp;")
                if (r.bold) s = "<b>$s</b>"
                if (r.italic) s = "<i>$s</i>"
                if (r.underline) s = "<u>$s</u>"
                val style = buildString {
                    r.color?.let { append("color:#%06x;".format(it)) }
                    r.sizePt?.let { append("font-size:${it}pt;") }
                }
                if (style.isNotEmpty()) s = "<span style=\"$style\">$s</span>"
                sb.append(s)
            }
            sb.append("</$t>")
        }
        for (b in doc.blocks) when (b) {
            is Para -> para(b)
            is Picture -> {
                val mime = if (b.bytes.size > 3 && b.bytes[0] == 0x89.toByte()) "image/png" else "image/jpeg"
                sb.append("<p><img src=\"data:$mime;base64,").append(Base64.encodeToString(b.bytes, Base64.NO_WRAP)).append("\"></p>")
            }
            is Table -> {
                sb.append("<table>")
                for (row in b.rows) {
                    sb.append("<tr>")
                    for (cell in row) {
                        sb.append("<td>")
                        cell.forEach { para(it, "div") }
                        sb.append("</td>")
                    }
                    sb.append("</tr>")
                }
                sb.append("</table>")
            }
            PageBreak -> sb.append("<hr>")
        }
        sb.append("</body></html>")
        return Storage.save(context, "$baseName.html", "text/html") { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
    }

    /** Saves edits made in the Word editor, keeping the original formatting. */
    fun saveEditedDocx(context: Context, baseName: String, editor: DocxEditor, texts: List<String>, asPdf: Boolean): SavedFile {
        editor.apply(texts)
        return if (asPdf) toPdf(context, baseName, editor.model())
        else Storage.save(context, "$baseName.docx", Storage.mimeFor("docx")) { editor.save(it) }
    }

    fun openEditor(context: Context, f: InputFile): DocxEditor = Storage.open(context, f).use { DocxEditor(it) }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Used by PDF to Word: plain runs to Word. */
    fun writeDocx(context: Context, name: String, writer: DocxWriter): SavedFile =
        Storage.save(context, name, Storage.mimeFor("docx")) { writer.write(it) }
}
