package io.github.harismushtaq12.dragwheel.ops.doc

import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/** A piece of text with one look. Sizes are in points. Colour is 0xRRGGBB or null for black. */
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val sizePt: Float? = null,
    val color: Int? = null,
)

sealed interface Block

/** align: 0 left, 1 centre, 2 right, 3 justified. heading: 0 normal, 1..6 heading, 9 title. */
data class Para(
    val runs: List<Run>,
    val heading: Int = 0,
    val align: Int = 0,
    val bullet: String? = null,
    val spaceAfterPt: Float = 6f,
) : Block

class Picture(val bytes: ByteArray, val widthPt: Float, val heightPt: Float) : Block

/** rows -> cells -> paragraphs */
class Table(val rows: List<List<List<Para>>>) : Block

data object PageBreak : Block

class DocxDoc(val blocks: List<Block>, val defaultSizePt: Float = 11f)

/** Reads a .docx into blocks for showing and for making a PDF. Keeps bold, italic, sizes, colours, headings, tables and pictures. */
object DocxReader {
    private const val MAX_BYTES = 120L * 1024 * 1024

    fun readEntries(input: InputStream): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val bytes = zip.readBytes()
                total += bytes.size
                if (total > MAX_BYTES) throw IllegalStateException("This document is too big to open on the phone.")
                entries[e.name] = bytes
            }
        }
        if ("word/document.xml" !in entries) throw IllegalStateException("This does not look like a Word (.docx) file.")
        return entries
    }

    fun parseXml(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false // keep "w:" names exactly, which also makes saving back safe
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder().parse(bytes.inputStream())
    }

    fun read(input: InputStream): DocxDoc = read(readEntries(input))

    fun read(entries: Map<String, ByteArray>): DocxDoc {
        val doc = parseXml(entries.getValue("word/document.xml"))
        val rels = relationships(entries)
        val defaultSize = defaultFontSize(entries)
        val body = firstChild(doc.documentElement, "w:body") ?: return DocxDoc(emptyList(), defaultSize)
        val out = mutableListOf<Block>()
        Walker(entries, rels, out).blockChildren(body)
        return DocxDoc(out, defaultSize)
    }

    private fun relationships(entries: Map<String, ByteArray>): Map<String, String> {
        val bytes = entries["word/_rels/document.xml.rels"] ?: return emptyMap()
        val d = parseXml(bytes)
        val map = HashMap<String, String>()
        val list = d.getElementsByTagName("Relationship")
        for (i in 0 until list.length) {
            val e = list.item(i) as Element
            var target = e.getAttribute("Target")
            target = if (target.startsWith("/")) target.drop(1) else "word/$target"
            map[e.getAttribute("Id")] = normalize(target)
        }
        return map
    }

    private fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            else -> out += p
        }
        return out.joinToString("/")
    }

    private fun defaultFontSize(entries: Map<String, ByteArray>): Float {
        val bytes = entries["word/styles.xml"] ?: return 11f
        return runCatching {
            val d = parseXml(bytes)
            val defaults = d.getElementsByTagName("w:rPrDefault")
            if (defaults.length == 0) return 11f
            val sz = (defaults.item(0) as Element).getElementsByTagName("w:sz")
            if (sz.length == 0) 11f else (sz.item(0) as Element).getAttribute("w:val").toFloat() / 2f
        }.getOrDefault(11f)
    }

    fun firstChild(e: Element, name: String): Element? {
        var n = e.firstChild
        while (n != null) {
            if (n is Element && n.tagName == name) return n
            n = n.nextSibling
        }
        return null
    }

    private fun on(e: Element?, name: String): Boolean {
        val x = e?.let { firstChild(it, name) } ?: return false
        val v = x.getAttribute("w:val")
        return v.isEmpty() || (v != "0" && v != "false" && v != "none")
    }

    private class Walker(
        val entries: Map<String, ByteArray>,
        val rels: Map<String, String>,
        val out: MutableList<Block>,
    ) {
        fun blockChildren(parent: Element) {
            var n: Node? = parent.firstChild
            while (n != null) {
                if (n is Element) when (n.tagName) {
                    "w:p" -> paragraph(n, out)
                    "w:tbl" -> out += table(n)
                    "w:sdt" -> firstChild(n, "w:sdtContent")?.let { blockChildren(it) }
                    "w:customXml", "w:ins" -> blockChildren(n)
                }
                n = n.nextSibling
            }
        }

        private fun table(t: Element): Table {
            val rows = mutableListOf<List<List<Para>>>()
            var r: Node? = t.firstChild
            while (r != null) {
                if (r is Element && r.tagName == "w:tr") {
                    val cells = mutableListOf<List<Para>>()
                    var c: Node? = r.firstChild
                    while (c != null) {
                        if (c is Element && c.tagName == "w:tc") {
                            val inner = mutableListOf<Block>()
                            Walker(entries, rels, inner).blockChildren(c)
                            cells += inner.filterIsInstance<Para>().ifEmpty { listOf(Para(emptyList())) }
                        }
                        c = c.nextSibling
                    }
                    if (cells.isNotEmpty()) rows += cells
                }
                r = r.nextSibling
            }
            return Table(rows)
        }

        fun paragraph(p: Element, sink: MutableList<Block>) {
            val pPr = firstChild(p, "w:pPr")
            val style = pPr?.let { firstChild(it, "w:pStyle") }?.getAttribute("w:val")?.lowercase() ?: ""
            val heading = when {
                style == "title" -> 9
                style.startsWith("heading") -> style.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 6) ?: 1
                else -> 0
            }
            val align = when (pPr?.let { firstChild(it, "w:jc") }?.getAttribute("w:val")) {
                "center" -> 1
                "right", "end" -> 2
                "both", "distribute" -> 3
                else -> 0
            }
            val bullet = if (pPr?.let { firstChild(it, "w:numPr") } != null || style.contains("list")) "•  " else null
            val after = pPr?.let { firstChild(it, "w:spacing") }?.getAttribute("w:after")?.toFloatOrNull()?.div(20f)
            if (pPr?.let { firstChild(it, "w:pageBreakBefore") } != null) sink += PageBreak

            val runs = mutableListOf<Run>()
            var pendingBreak = false
            fun flush(final: Boolean) {
                if (runs.isNotEmpty() || final) {
                    sink += Para(runs.toList(), heading, align, bullet, after ?: if (heading > 0) 8f else 6f)
                    runs.clear()
                }
            }

            fun walk(node: Element) {
                var n: Node? = node.firstChild
                while (n != null) {
                    if (n is Element) when (n.tagName) {
                        "w:r" -> run(n, runs) { picture ->
                            flush(false)
                            sink += picture
                        }.also { if (it) pendingBreak = true }
                        "w:hyperlink", "w:ins", "w:smartTag", "w:fldSimple", "w:customXml" -> walk(n)
                        "w:sdt" -> firstChild(n, "w:sdtContent")?.let { walk(it) }
                        "mc:AlternateContent" -> firstChild(n, "mc:Choice")?.let { walk(it) }
                    }
                    n = n.nextSibling
                }
            }
            walk(p)
            flush(true)
            if (pendingBreak) sink += PageBreak
        }

        /** Adds the run's text; calls [onPicture] for pictures. Returns true if it ends with a page break. */
        private fun run(r: Element, runs: MutableList<Run>, onPicture: (Picture) -> Unit): Boolean {
            val rPr = firstChild(r, "w:rPr")
            val bold = on(rPr, "w:b")
            val italic = on(rPr, "w:i")
            val underline = on(rPr, "w:u")
            val size = rPr?.let { firstChild(it, "w:sz") }?.getAttribute("w:val")?.toFloatOrNull()?.div(2f)
            val color = rPr?.let { firstChild(it, "w:color") }?.getAttribute("w:val")
                ?.takeIf { it.length == 6 && it != "auto" }?.toIntOrNull(16)
            val sb = StringBuilder()
            var pageBreak = false

            fun emit() {
                if (sb.isNotEmpty()) {
                    runs += Run(sb.toString(), bold, italic, underline, size, color)
                    sb.setLength(0)
                }
            }

            var n: Node? = r.firstChild
            while (n != null) {
                if (n is Element) when (n.tagName) {
                    "w:t" -> sb.append(n.textContent)
                    "w:tab", "w:ptab" -> sb.append('\t')
                    "w:br" -> if (n.getAttribute("w:type") == "page") pageBreak = true else sb.append('\n')
                    "w:cr" -> sb.append('\n')
                    "w:noBreakHyphen" -> sb.append('-')
                    "w:drawing", "w:pict", "mc:AlternateContent" -> picture(n)?.let { pic ->
                        emit()
                        onPicture(pic)
                    }
                }
                n = n.nextSibling
            }
            emit()
            return pageBreak
        }

        private fun picture(e: Element): Picture? {
            val blips = e.getElementsByTagName("a:blip")
            val id = if (blips.length > 0) (blips.item(0) as Element).getAttribute("r:embed") else {
                val vml = e.getElementsByTagName("v:imagedata")
                if (vml.length > 0) (vml.item(0) as Element).getAttribute("r:id") else return null
            }
            val path = rels[id] ?: return null
            val bytes = entries[path] ?: return null
            val ext = e.getElementsByTagName("wp:extent")
            var w = 0f
            var h = 0f
            if (ext.length > 0) {
                val x = ext.item(0) as Element
                w = (x.getAttribute("cx").toFloatOrNull() ?: 0f) / 12700f
                h = (x.getAttribute("cy").toFloatOrNull() ?: 0f) / 12700f
            }
            return Picture(bytes, w, h)
        }
    }
}
