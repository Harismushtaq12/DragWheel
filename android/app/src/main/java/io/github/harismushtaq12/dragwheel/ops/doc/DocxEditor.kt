package io.github.harismushtaq12.dragwheel.ops.doc

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Edits the words of a Word file without touching its look.
 * Only the changed part of a changed paragraph is rewritten; fonts, bold, colours, tables,
 * pictures, headers and everything else stay exactly as they were.
 */
class DocxEditor(input: InputStream) {
    private val entries = DocxReader.readEntries(input)
    private val doc: Document = DocxReader.parseXml(entries.getValue("word/document.xml"))
    private val paragraphs: List<Element>

    private val current: MutableList<String>

    /** Text of each paragraph as it is now, in document order (tables included). */
    val texts: List<String> get() = current

    init {
        val all = doc.getElementsByTagName("w:p")
        val list = ArrayList<Element>()
        for (i in 0 until all.length) {
            val p = all.item(i) as Element
            if (!insideFallback(p)) list += p
        }
        paragraphs = list
        current = list.map { segments(it).joinToString("") { s -> s.text } }.toMutableList()
    }

    private fun insideFallback(n: Node): Boolean {
        var p = n.parentNode
        while (p != null) {
            if (p is Element && p.tagName == "mc:Fallback") return true
            p = p.parentNode
        }
        return false
    }

    /** A piece of a paragraph: real text (editable node) or a tab/line break. */
    private class Seg(val node: Element, val text: String, val isText: Boolean)

    private fun segments(p: Element): List<Seg> {
        val out = ArrayList<Seg>()
        fun walk(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element) when (c.tagName) {
                    "w:t" -> out += Seg(c, c.textContent, true)
                    "w:tab" -> if (c.parentNode is Element && (c.parentNode as Element).tagName == "w:r") out += Seg(c, "\t", false)
                    "w:br" -> if (c.getAttribute("w:type") != "page") out += Seg(c, "\n", false)
                    "w:cr" -> out += Seg(c, "\n", false)
                    // Do not look inside text boxes (their paragraphs are listed on their own) or old copies.
                    "w:txbxContent", "mc:Fallback", "w:pPr", "w:rPr", "w:del", "w:instrText" -> Unit
                    else -> walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(p)
        return out
    }

    /** Applies all changed paragraphs. [newTexts] must line up with [texts]. */
    fun apply(newTexts: List<String>): Int {
        var changed = 0
        for (i in paragraphs.indices) {
            val new = newTexts.getOrNull(i) ?: continue
            if (new != current[i]) {
                setText(paragraphs[i], current[i], new)
                current[i] = new
                changed++
            }
        }
        return changed
    }

    private fun setText(p: Element, old: String, new: String) {
        if (segments(p).none { it.isText }) {
            addRun(p, new)
            return
        }
        // Apply each small change from the end backwards, so earlier positions stay valid.
        for (h in hunks(old, new).asReversed()) applyHunk(p, h.start, h.end, h.insert)
    }

    /** Replace old[start, end) with insert. */
    private class Hunk(val start: Int, val end: Int, val insert: String)

    /**
     * Finds the smallest changes between two texts (longest common subsequence),
     * so untouched words keep their own formatting.
     */
    private fun hunks(old: String, new: String): List<Hunk> {
        var pre = 0
        while (pre < old.length && pre < new.length && old[pre] == new[pre]) pre++
        var sufO = old.length
        var sufN = new.length
        while (sufO > pre && sufN > pre && old[sufO - 1] == new[sufN - 1]) {
            sufO--; sufN--
        }
        val a = old.substring(pre, sufO)
        val b = new.substring(pre, sufN)
        if (a.isEmpty() || b.isEmpty() || a.length.toLong() * b.length > 4_000_000L) {
            return listOf(Hunk(pre, sufO, b))
        }
        val n = a.length
        val m = b.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val raw = ArrayList<Hunk>()
        var i = 0
        var j = 0
        var hs = -1
        val ins = StringBuilder()
        fun close(at: Int) {
            if (hs >= 0) {
                raw += Hunk(pre + hs, pre + at, ins.toString())
                hs = -1
                ins.setLength(0)
            }
        }
        while (i < n || j < m) {
            if (i < n && j < m && a[i] == b[j]) {
                close(i); i++; j++
            } else if (j < m && (i == n || dp[i][j + 1] >= dp[i + 1][j])) {
                if (hs < 0) hs = i
                ins.append(b[j]); j++
            } else {
                if (hs < 0) hs = i
                i++
            }
        }
        close(i)
        // Join changes that are only one or two letters apart, so words are not chopped up.
        val merged = ArrayList<Hunk>()
        for (h in raw) {
            val last = merged.lastOrNull()
            if (last != null && h.start - last.end <= 2) {
                merged[merged.lastIndex] = Hunk(last.start, h.end, last.insert + old.substring(last.end, h.start) + h.insert)
            } else merged += h
        }
        return merged
    }

    private fun applyHunk(p: Element, start: Int, endOld: Int, insert: String) {
        val segs = segments(p)
        val removing = endOld > start
        var pos = 0
        var inserted = false
        for (s in segs) {
            val sStart = pos
            val sEnd = pos + s.text.length
            pos = sEnd
            if (s.isText) {
                val touches = if (removing) sStart < endOld && sEnd > start else !inserted && sStart <= start && start <= sEnd
                if (!touches) continue
                val len = s.text.length
                val before = s.text.substring(0, (start - sStart).coerceIn(0, len))
                val after = s.text.substring((endOld - sStart).coerceIn(0, len))
                val mid = if (!inserted) insert.also { inserted = true } else ""
                setT(s.node, before + mid + after)
            } else if (removing && sStart >= start && sEnd <= endOld) {
                // a tab or line break inside the removed part
                s.node.parentNode?.removeChild(s.node)
            }
        }
        if (!inserted && insert.isNotEmpty()) {
            // The change only touched tabs or breaks: put the new text in the nearest text piece.
            val target = segs.firstOrNull { it.isText && it.node.parentNode != null }
            target?.let { setT(it.node, it.node.textContent + insert) }
        }
    }

    private fun setT(t: Element, value: String) {
        t.textContent = value
        t.setAttribute("xml:space", "preserve")
    }

    private fun addRun(p: Element, text: String) {
        if (text.isEmpty()) return
        val r = doc.createElement("w:r")
        val firstRun = p.getElementsByTagName("w:r").item(0) as? Element
        val rPr = firstRun?.let { DocxReader.firstChild(it, "w:rPr") }
        if (rPr != null) r.appendChild(rPr.cloneNode(true))
        val t = doc.createElement("w:t")
        setT(t, text)
        r.appendChild(t)
        p.appendChild(r)
    }

    /** Counts matches of [find] across all paragraphs. */
    fun count(texts: List<String>, find: String, matchCase: Boolean): Int =
        if (find.isEmpty()) 0 else texts.sumOf { countIn(it, find, matchCase) }

    private fun countIn(s: String, find: String, matchCase: Boolean): Int {
        var n = 0
        var i = s.indexOf(find, 0, !matchCase)
        while (i >= 0) {
            n++; i = s.indexOf(find, i + find.length, !matchCase)
        }
        return n
    }

    fun save(out: OutputStream) {
        val xml = ByteArrayOutputStream()
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.STANDALONE, "yes")
        t.transform(DOMSource(doc), StreamResult(xml))
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (name == "word/document.xml") xml.toByteArray() else data)
                zip.closeEntry()
            }
        }
    }

    /** The edited document as blocks, for the PDF and the preview. */
    fun model(): DocxDoc {
        val bytes = ByteArrayOutputStream().also { save(it) }.toByteArray()
        return DocxReader.read(bytes.inputStream())
    }
}
