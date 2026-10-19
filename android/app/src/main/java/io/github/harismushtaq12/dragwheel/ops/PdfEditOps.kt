package io.github.harismushtaq12.dragwheel.ops

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import io.github.harismushtaq12.dragwheel.ops.doc.DocxWriter
import io.github.harismushtaq12.dragwheel.ops.doc.Run
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Positions in the PDF editor are "display" coordinates from 0 to 1, as the page looks on screen:
 * (0,0) is the top-left corner, (1,1) the bottom-right. They are turned into PDF points when saving.
 */

/** Something the user added on a page. */
sealed interface Mark {
    val page: Int
}

/** A freehand line (drawing or signature). Width is a share of the page width. */
data class InkMark(override val page: Int, val points: List<Pair<Float, Float>>, val color: Int, val width: Float) : Mark

/** A filled box: white to hide what is under it, or see-through yellow to highlight. */
data class BoxMark(override val page: Int, val l: Float, val t: Float, val r: Float, val b: Float, val highlight: Boolean) : Mark

/** Text placed with its top-left corner at (x, y). [cover] hides old text first (l, t, r, b). */
data class TextMark(
    override val page: Int,
    val x: Float,
    val y: Float,
    val text: String,
    val sizePt: Float,
    val bold: Boolean,
    val color: Int,
    val cover: List<Float>? = null,
) : Mark

/** A line of existing text, so it can be tapped and changed. */
data class TextLine(val l: Float, val t: Float, val r: Float, val b: Float, val text: String, val sizePt: Float, val bold: Boolean)

/** A form field to fill in. kind: 0 text, 1 tick box, 2 pick one. */
data class FormField(val name: String, val kind: Int, val value: String, val options: List<String>)

object PdfEditOps {

    // ---------- Page geometry ----------

    private class Geo(page: PDPage) {
        val cb = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val dispW = if (rot % 180 == 0) cb.width else cb.height
        val dispH = if (rot % 180 == 0) cb.height else cb.width

        /** Display (u, v) to PDF points. */
        fun map(u: Float, v: Float): Pair<Float, Float> {
            val (xn, yn) = when (rot) {
                90 -> v to 1 - u
                180 -> 1 - u to 1 - v
                270 -> 1 - v to u
                else -> u to v
            }
            return cb.lowerLeftX + xn * cb.width to cb.upperRightY - yn * cb.height
        }

        fun rect(l: Float, t: Float, r: Float, b: Float): FloatArray {
            val (x1, y1) = map(l, t)
            val (x2, y2) = map(r, b)
            return floatArrayOf(min(x1, x2), min(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))
        }
    }

    // ---------- Fonts ----------

    private class Fonts(private val doc: PDDocument) {
        private val dir = "/system/fonts/"
        val regular: PDFont by lazy { load(listOf("Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf")) ?: PDType1Font.HELVETICA }
        val bold: PDFont by lazy { load(listOf("Roboto-Bold.ttf", "RobotoStatic-Bold.ttf", "NotoSans-Bold.ttf", "DroidSans-Bold.ttf")) ?: regular }

        private fun load(names: List<String>): PDFont? {
            for (n in names) {
                val f = File(dir + n)
                if (f.exists()) runCatching { return PDType0Font.load(doc, f) }
            }
            return null
        }

        /** Characters the font cannot draw become "?" instead of failing. */
        fun safe(font: PDFont, text: String): String = buildString {
            for (ch in text) {
                if (ch == '\t') { append("    "); continue }
                val ok = runCatching { font.encode(ch.toString()) }.isSuccess
                append(if (ok) ch else '?')
            }
        }
    }

    private fun rgb(color: Int) = Triple(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)

    private fun drawText(
        cs: PDPageContentStream, g: Geo, fonts: Fonts, font: PDFont,
        u: Float, v: Float, text: String, size: Float, color: Int, extraAngle: Double = 0.0,
    ) {
        val (r, gr, b) = rgb(color)
        cs.setNonStrokingColor(r, gr, b)
        text.split('\n').forEachIndexed { i, line ->
            if (line.isEmpty()) return@forEachIndexed
            val baselineV = v + (size + i * size * 1.25f) / g.dispH
            val (px, py) = g.map(u, baselineV)
            cs.beginText()
            cs.setFont(font, size)
            cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(g.rot.toDouble()) + extraAngle, px, py))
            cs.showText(fonts.safe(font, line))
            cs.endText()
        }
    }

    // ---------- Edit PDF ----------

    /** Lines of text on each page (pages that are not turned), so the user can tap to change them. */
    fun textLines(context: Context, file: InputFile): Map<Int, List<TextLine>> {
        PdfOps.load(context, file).use { doc ->
            val result = HashMap<Int, MutableList<TextLine>>()
            val stripper = object : PDFTextStripper() {
                private val words = StringBuilder()
                private var l = 0f
                private var t = 0f
                private var r = 0f
                private var b = 0f
                private var size = 0f
                private var bold = false
                private var any = false

                override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                    val pos = textPositions ?: return
                    if (pos.isEmpty() || text.isNullOrEmpty()) return
                    val page = currentPage ?: return
                    if (((page.rotation % 360) + 360) % 360 != 0) return
                    val w = page.cropBox.width
                    val h = page.cropBox.height
                    for (p in pos) {
                        val x0 = p.xDirAdj / w
                        val x1 = (p.xDirAdj + p.widthDirAdj) / w
                        val y1 = p.yDirAdj / h
                        val y0 = (p.yDirAdj - max(p.heightDir, p.fontSizeInPt * 0.7f)) / h
                        if (!any) { l = x0; t = y0; r = x1; b = y1; any = true } else {
                            l = min(l, x0); t = min(t, y0); r = max(r, x1); b = max(b, y1)
                        }
                        size = max(size, p.fontSizeInPt)
                        if (p.font?.name?.contains("bold", ignoreCase = true) == true) bold = true
                    }
                    if (words.isNotEmpty()) words.append(' ')
                    words.append(text)
                }

                override fun writeLineSeparator() = flush()

                override fun endPage(page: PDPage?) {
                    flush()
                    super.endPage(page)
                }

                private fun flush() {
                    if (any && words.isNotBlank()) {
                        result.getOrPut(currentPageNo - 1) { mutableListOf() } +=
                            TextLine(l, t, r, b, words.toString(), if (size > 0) size else 11f, bold)
                    }
                    words.setLength(0)
                    any = false
                    size = 0f
                    bold = false
                }
            }
            stripper.sortByPosition = true
            stripper.getText(doc)
            return result
        }
    }

    /** Saves the user's additions into the PDF. Everything already in the PDF stays as it was. */
    fun applyMarks(context: Context, file: InputFile, marks: List<Mark>): SavedFile {
        require(marks.isNotEmpty()) { "Nothing has been changed yet." }
        PdfOps.load(context, file).use { doc ->
            val fonts = Fonts(doc)
            for ((index, list) in marks.groupBy { it.page }) {
                if (index !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(index)
                val g = Geo(page)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (m in list) when (m) {
                        is BoxMark -> {
                            cs.saveGraphicsState()
                            if (m.highlight) {
                                val gs = PDExtendedGraphicsState()
                                gs.nonStrokingAlphaConstant = 0.35f
                                cs.setGraphicsStateParameters(gs)
                                cs.setNonStrokingColor(1f, 0.92f, 0.2f)
                            } else cs.setNonStrokingColor(1f, 1f, 1f)
                            val rc = g.rect(m.l, m.t, m.r, m.b)
                            cs.addRect(rc[0], rc[1], rc[2], rc[3])
                            cs.fill()
                            cs.restoreGraphicsState()
                        }
                        is InkMark -> if (m.points.size >= 2) {
                            val (r, gr, b) = rgb(m.color)
                            cs.saveGraphicsState()
                            cs.setStrokingColor(r, gr, b)
                            cs.setLineWidth(max(0.5f, m.width * g.dispW))
                            cs.setLineCapStyle(1)
                            cs.setLineJoinStyle(1)
                            val (x0, y0) = g.map(m.points[0].first, m.points[0].second)
                            cs.moveTo(x0, y0)
                            for (k in 1 until m.points.size) {
                                val (x, y) = g.map(m.points[k].first, m.points[k].second)
                                cs.lineTo(x, y)
                            }
                            cs.stroke()
                            cs.restoreGraphicsState()
                        }
                        is TextMark -> {
                            m.cover?.let { c ->
                                cs.saveGraphicsState()
                                cs.setNonStrokingColor(1f, 1f, 1f)
                                val rc = g.rect(c[0], c[1], c[2], c[3])
                                cs.addRect(rc[0], rc[1], rc[2], rc[3])
                                cs.fill()
                                cs.restoreGraphicsState()
                            }
                            if (m.text.isNotBlank()) {
                                drawText(cs, g, fonts, if (m.bold) fonts.bold else fonts.regular, m.x, m.y, m.text, m.sizePt, m.color)
                            }
                        }
                    }
                }
            }
            return PdfOps.write(context, doc, "${file.baseName} edited.pdf")
        }
    }

    // ---------- Forms ----------

    fun formFields(context: Context, file: InputFile): List<FormField> {
        PdfOps.load(context, file).use { doc ->
            val form = doc.documentCatalog.acroForm ?: return emptyList()
            val out = mutableListOf<FormField>()
            for (f in form.fieldTree) {
                val name = f.fullyQualifiedName ?: continue
                when (f) {
                    is PDTextField -> out += FormField(name, 0, f.value ?: "", emptyList())
                    is PDCheckBox -> out += FormField(name, 1, if (f.isChecked) "true" else "false", emptyList())
                    is PDRadioButton -> out += FormField(name, 2, f.value ?: "", f.onValues.toList())
                    is PDChoice -> out += FormField(name, 2, f.value.firstOrNull() ?: "", f.options)
                }
            }
            return out
        }
    }

    fun fillForm(context: Context, file: InputFile, values: Map<String, String>, flatten: Boolean): SavedFile {
        PdfOps.load(context, file).use { doc ->
            val form = doc.documentCatalog.acroForm ?: throw IllegalStateException("This PDF has no form to fill in.")
            val problems = mutableListOf<String>()
            for ((name, value) in values) {
                val f = form.getField(name) ?: continue
                try {
                    when (f) {
                        is PDCheckBox -> if (value == "true") f.check() else f.unCheck()
                        is PDRadioButton -> if (value.isNotEmpty()) f.value = value
                        is PDChoice -> if (value.isNotEmpty()) f.setValue(value)
                        is PDTextField -> f.value = value
                    }
                } catch (e: Exception) {
                    problems += name
                }
            }
            if (problems.isNotEmpty()) form.needAppearances = true
            if (flatten) runCatching { form.flatten() }
            return PdfOps.write(context, doc, "${file.baseName} filled.pdf")
        }
    }

    // ---------- Tools ----------

    /** Saves big pictures inside the PDF again at a smaller size. Text stays sharp. */
    fun compress(context: Context, file: InputFile, quality: Int, maxSide: Int): SavedFile {
        PdfOps.load(context, file).use { doc ->
            val done = HashMap<COSBase, PDImageXObject>()
            var changed = 0
            for (page in doc.pages) {
                val res = page.resources ?: continue
                for (name in res.xObjectNames.toList()) {
                    val x = runCatching { res.getXObject(name) }.getOrNull() as? PDImageXObject ?: continue
                    if (x.softMask != null) continue // see-through pictures would get a black background
                    val key = x.cosObject
                    val replacement = done.getOrPut(key) {
                        try {
                            val bmp = x.image ?: return@getOrPut x
                            val scale = min(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
                            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * scale).toInt()), max(1, (bmp.height * scale).toInt()), true) else bmp
                            val jpg = JPEGFactory.createFromImage(doc, scaled, quality / 100f)
                            if (jpg.cosObject.length < x.cosObject.length) jpg.also { changed++ } else x
                        } catch (e: Exception) {
                            x
                        }
                    }
                    if (replacement !== x) res.put(name, replacement)
                }
            }
            if (changed == 0) throw IllegalStateException("This PDF has no big pictures to shrink, so it is already as small as this tool can make it.")
            return PdfOps.write(context, doc, "${file.baseName} compressed.pdf")
        }
    }

    fun pageNumbers(context: Context, file: InputFile, style: String, position: String): SavedFile {
        PdfOps.load(context, file).use { doc ->
            val fonts = Fonts(doc)
            val n = doc.numberOfPages
            for (i in 0 until n) {
                val page = doc.getPage(i)
                val g = Geo(page)
                val text = when (style) {
                    "of" -> "${i + 1} of $n"
                    "page" -> "Page ${i + 1}"
                    else -> "${i + 1}"
                }
                val size = 10f
                val width = fonts.regular.getStringWidth(fonts.safe(fonts.regular, text)) / 1000f * size
                val u = when (position) {
                    "left" -> 36f / g.dispW
                    "right" -> 1f - (36f + width) / g.dispW
                    else -> 0.5f - width / 2f / g.dispW
                }
                val v = 1f - (24f + size) / g.dispH
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    drawText(cs, g, fonts, fonts.regular, u, v, text, size, Color.rgb(60, 60, 60))
                }
            }
            return PdfOps.write(context, doc, "${file.baseName} numbered.pdf")
        }
    }

    fun watermark(context: Context, file: InputFile, text: String): SavedFile {
        require(text.isNotBlank()) { "Type the watermark text." }
        PdfOps.load(context, file).use { doc ->
            val fonts = Fonts(doc)
            for (page in doc.pages) {
                val g = Geo(page)
                val size = min(g.dispW, g.dispH) / max(6, text.length) * 1.6f
                val width = fonts.bold.getStringWidth(fonts.safe(fonts.bold, text)) / 1000f * size
                val angle = Math.toRadians(35.0)
                // Start point so the text is centred, going up at 35 degrees.
                val cx = 0.5f * g.dispW - (width / 2f) * kotlin.math.cos(angle).toFloat()
                val cy = 0.5f * g.dispH + (width / 2f) * kotlin.math.sin(angle).toFloat()
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.saveGraphicsState()
                    val gs = PDExtendedGraphicsState()
                    gs.nonStrokingAlphaConstant = 0.18f
                    cs.setGraphicsStateParameters(gs)
                    val (px, py) = g.map(cx / g.dispW, cy / g.dispH)
                    cs.setNonStrokingColor(0.5f, 0.5f, 0.5f)
                    cs.beginText()
                    cs.setFont(fonts.bold, size)
                    cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(g.rot.toDouble()) + angle, px, py))
                    cs.showText(fonts.safe(fonts.bold, text))
                    cs.endText()
                    cs.restoreGraphicsState()
                }
            }
            return PdfOps.write(context, doc, "${file.baseName} watermarked.pdf")
        }
    }

    // ---------- Conversions ----------

    /** PDF to an editable Word file: text, paragraphs, bold and bigger titles. */
    fun toWordEditable(context: Context, file: InputFile): SavedFile {
        class Word(val text: String, val bold: Boolean, val size: Float)
        class Line(val page: Int, val words: List<Word>, val top: Float, val bottom: Float)

        val lines = mutableListOf<Line>()
        PdfOps.load(context, file).use { doc ->
            val stripper = object : PDFTextStripper() {
                private val words = mutableListOf<Word>()
                private var top = Float.MAX_VALUE
                private var bottom = 0f

                override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                    val pos = textPositions ?: return
                    if (text.isNullOrEmpty() || pos.isEmpty()) return
                    val bold = pos.any { it.font?.name?.contains("bold", true) == true }
                    val size = pos.maxOf { it.fontSizeInPt }
                    words += Word(text, bold, size)
                    for (p in pos) {
                        top = min(top, p.yDirAdj - p.heightDir)
                        bottom = max(bottom, p.yDirAdj)
                    }
                }

                override fun writeLineSeparator() = flush()

                override fun endPage(page: PDPage?) {
                    flush()
                    super.endPage(page)
                }

                private fun flush() {
                    if (words.isNotEmpty()) lines += Line(currentPageNo - 1, words.toList(), top, bottom)
                    words.clear()
                    top = Float.MAX_VALUE
                    bottom = 0f
                }
            }
            stripper.sortByPosition = true
            stripper.getText(doc)
        }
        if (lines.isEmpty()) throw IllegalStateException(
            "No text found. This PDF is probably scanned pictures. Try \"Word (exact look)\" or \"Scan to text\"."
        )

        // The most common size is normal body text.
        val body = lines.flatMap { it.words }.groupingBy { it.size.roundToInt() }.eachCount().maxByOrNull { it.value }?.key?.toFloat() ?: 11f

        val w = DocxWriter()
        var paragraph = mutableListOf<Word>()
        var lastPage = 0
        var last: Line? = null

        fun flush() {
            if (paragraph.isEmpty()) return
            val runs = mutableListOf<Run>()
            for (word in paragraph) {
                val size = if (kotlin.math.abs(word.size - body) < 1f) null else word.size
                val prev = runs.lastOrNull()
                if (prev != null && prev.bold == word.bold && prev.sizePt == size) {
                    runs[runs.lastIndex] = prev.copy(text = prev.text + " " + word.text)
                } else {
                    runs += Run((if (runs.isEmpty()) "" else " ") + word.text, bold = word.bold, sizePt = size)
                }
            }
            val big = paragraph.maxOf { it.size } >= body * 1.3f
            w.paragraph(runs, heading = if (big) 1 else 0, spaceAfterPt = if (big) 10f else 6f)
            paragraph = mutableListOf()
        }

        for (line in lines) {
            if (line.page != lastPage) {
                flush()
                w.pageBreak()
                lastPage = line.page
                last = null
            }
            val prev = last
            val height = max(1f, line.bottom - line.top)
            val gap = if (prev == null) 0f else line.top - prev.bottom
            val sizeChanged = prev != null && kotlin.math.abs(prev.words.maxOf { it.size } - line.words.maxOf { it.size }) > 1f
            if (prev != null && (gap > height * 0.9f || sizeChanged)) flush()
            paragraph += line.words
            last = line
        }
        flush()
        return DocOps.writeDocx(context, "${file.baseName}.docx", w)
    }

    /** PDF to Word where every page is a picture: looks exactly the same, but the words cannot be edited. */
    fun toWordExact(context: Context, file: InputFile): SavedFile {
        val local = Storage.localCopy(context, file)
        val w = DocxWriter()
        w.marginPt = 0f
        PdfOps.openRenderer(local).use { r ->
            for (i in 0 until r.pageCount) {
                r.openPage(i).use { page ->
                    if (i == 0) {
                        w.pageWidthPt = page.width.toFloat()
                        w.pageHeightPt = page.height.toFloat()
                    } else w.pageBreak()
                    val scale = 150f / 72f
                    val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                    bmp.recycle()
                    w.image(bytes, "jpeg", page.width * 0.98f, page.height * 0.98f)
                }
            }
        }
        return DocOps.writeDocx(context, "${file.baseName} (exact).docx", w)
    }

    /** All pages one under the other, as one tall picture. */
    fun longImage(context: Context, file: InputFile, png: Boolean): SavedFile {
        val local = Storage.localCopy(context, file)
        PdfOps.openRenderer(local).use { r ->
            val width = 1080
            val heights = (0 until r.pageCount).map { i -> r.openPage(i).use { (width * it.height.toFloat() / it.width).toInt() } }
            val total = heights.sum()
            val scale = min(1f, 15000f / total)
            val outW = (width * scale).toInt().coerceAtLeast(1)
            val outH = (total * scale).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            out.eraseColor(Color.WHITE)
            val canvas = android.graphics.Canvas(out)
            var y = 0
            for (i in 0 until r.pageCount) {
                val h = (heights[i] * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(outW, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                r.openPage(i).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                canvas.drawBitmap(bmp, 0f, y.toFloat(), null)
                bmp.recycle()
                y += h
            }
            val ext = if (png) "png" else "jpg"
            return Storage.save(context, "${file.baseName} long.$ext") { o ->
                out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, o)
            }.also { out.recycle() }
        }
    }

    /** Saves the pictures that are inside the PDF, at their real size. */
    fun extractImages(context: Context, file: InputFile): List<SavedFile> {
        PdfOps.load(context, file).use { doc ->
            val seen = HashSet<COSBase>()
            val out = mutableListOf<SavedFile>()
            doc.pages.forEachIndexed { i, page ->
                val res = page.resources ?: return@forEachIndexed
                var n = 0
                for (name in res.xObjectNames) {
                    val x = runCatching { res.getXObject(name) }.getOrNull() as? PDImageXObject ?: continue
                    if (!seen.add(x.cosObject)) continue
                    val bmp = runCatching { x.image }.getOrNull() ?: continue
                    if (bmp.width < 16 || bmp.height < 16) continue
                    n++
                    val png = bmp.hasAlpha() || x.softMask != null
                    val ext = if (png) "png" else "jpg"
                    out += Storage.save(context, "page ${i + 1} picture $n.$ext", subfolder = "${file.baseName} pictures") { o ->
                        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, o)
                    }
                }
            }
            if (out.isEmpty()) throw IllegalStateException("No pictures were found inside this PDF.")
            return out
        }
    }

    /** Pages as pictures for reading text from scans. */
    fun renderForOcr(context: Context, file: InputFile, index: Int, r: PdfRenderer): Bitmap =
        r.openPage(index).use { page ->
            val scale = min(200f / 72f, 3000f / max(page.width, page.height))
            val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            bmp
        }
}
