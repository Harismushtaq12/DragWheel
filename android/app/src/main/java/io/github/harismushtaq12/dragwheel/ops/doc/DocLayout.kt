package io.github.harismushtaq12.dragwheel.ops.doc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import kotlin.math.max
import kotlin.math.min

/**
 * Lays a document out on A4 pages (sizes in points) and draws them.
 * Used for the formatted preview, Word to PDF, and Word to pictures, so all three look the same.
 */
class DocLayout(
    private val doc: DocxDoc,
    val pageW: Float = 595f,
    val pageH: Float = 842f,
    private val margin: Float = 56f,
) {
    private sealed interface Item

    private class Lines(val layout: StaticLayout, val from: Int, val to: Int, val x: Float, val y: Float) : Item
    private class Pic(val bitmap: Bitmap, val rect: RectF) : Item
    private class Row(val cells: List<StaticLayout>, val xs: List<Float>, val ws: List<Float>, val y: Float, val h: Float) : Item

    private val pages = mutableListOf<MutableList<Item>>(mutableListOf())
    val pageCount: Int get() = pages.size

    private val contentW = pageW - 2 * margin
    private val bottom = pageH - margin
    private var y = margin

    private val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = doc.defaultSizePt
    }

    init {
        for (b in doc.blocks) when (b) {
            is Para -> para(b)
            is Picture -> picture(b)
            is Table -> table(b)
            PageBreak -> newPage()
        }
        if (pages.size > 1 && pages.last().isEmpty()) pages.removeAt(pages.lastIndex)
    }

    private fun newPage() {
        pages += mutableListOf<Item>()
        y = margin
    }

    private fun spannable(p: Para): CharSequence {
        val sb = SpannableStringBuilder()
        p.bullet?.let { sb.append(it) }
        val headingSize = when (p.heading) {
            9 -> 26f
            1 -> 20f
            2 -> 16f
            3 -> 14f
            4, 5, 6 -> 12f
            else -> null
        }
        for (r in p.runs) {
            if (r.text.isEmpty()) continue
            val start = sb.length
            sb.append(r.text)
            val end = sb.length
            val bold = r.bold || p.heading > 0
            val style = when {
                bold && r.italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                r.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            if (style != Typeface.NORMAL) sb.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.underline) sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val size = r.sizePt ?: headingSize
            if (size != null && size != doc.defaultSizePt) {
                sb.setSpan(RelativeSizeSpan(size / doc.defaultSizePt), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            r.color?.let { c ->
                sb.setSpan(ForegroundColorSpan(0xFF000000.toInt() or c), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (sb.isEmpty()) sb.append(" ")
        return sb
    }

    private fun build(text: CharSequence, width: Int, align: Int): StaticLayout {
        val alignment = when (align) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val b = StaticLayout.Builder.obtain(text, 0, text.length, basePaint, max(1, width))
            .setAlignment(alignment)
            .setLineSpacing(0f, 1.15f)
        if (align == 3) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    private fun para(p: Para) {
        val layout = build(spannable(p), contentW.toInt(), p.align)
        var line = 0
        while (line < layout.lineCount) {
            val top = layout.getLineTop(line)
            var last = line - 1
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= bottom - y) last++
            if (last < line) {
                if (y > margin) {
                    newPage()
                    continue
                }
                last = line // a line taller than the page: place it anyway
            }
            pages.last() += Lines(layout, line, last, margin, y)
            y += layout.getLineBottom(last) - top
            line = last + 1
            if (line < layout.lineCount) newPage()
        }
        y += p.spaceAfterPt
    }

    private fun picture(p: Picture) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > 2000) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return
        var w = if (p.widthPt > 1) p.widthPt else opts.outWidth * 0.75f
        var h = if (p.heightPt > 1) p.heightPt else opts.outHeight * 0.75f
        val usable = bottom - margin
        val scale = min(1f, min(contentW / w, usable / h))
        w *= scale
        h *= scale
        if (y + h > bottom) newPage()
        pages.last() += Pic(bitmap, RectF(margin, y, margin + w, y + h))
        y += h + 6f
    }

    private fun table(t: Table) {
        val cols = t.rows.maxOfOrNull { it.size } ?: return
        if (cols == 0) return
        val colW = contentW / cols
        for (row in t.rows) {
            val cells = row.map { paras ->
                val sb = SpannableStringBuilder()
                paras.forEachIndexed { i, p ->
                    if (i > 0) sb.append('\n')
                    sb.append(spannable(p))
                }
                build(sb, (colW - 8f).toInt(), paras.firstOrNull()?.align ?: 0)
            }
            val h = (cells.maxOfOrNull { it.height } ?: 0) + 8f
            if (y + h > bottom && y > margin) newPage()
            val xs = cells.indices.map { margin + it * colW }
            pages.last() += Row(cells, xs, cells.indices.map { colW }, y, h)
            y += h
        }
        y += 8f
    }

    /** Draws page [index] onto [canvas], whose units are points (scale the canvas for pixels). */
    fun draw(canvas: Canvas, index: Int) {
        canvas.drawColor(Color.WHITE)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.6f
            color = Color.GRAY
        }
        for (item in pages.getOrNull(index) ?: return) when (item) {
            is Lines -> {
                val top = item.layout.getLineTop(item.from).toFloat()
                val bot = item.layout.getLineBottom(item.to).toFloat()
                canvas.save()
                canvas.translate(item.x, item.y - top)
                canvas.clipRect(0f, top, item.layout.width.toFloat(), bot)
                item.layout.draw(canvas)
                canvas.restore()
            }
            is Pic -> canvas.drawBitmap(item.bitmap, null, item.rect, Paint(Paint.FILTER_BITMAP_FLAG))
            is Row -> for (i in item.cells.indices) {
                canvas.drawRect(item.xs[i], item.y, item.xs[i] + item.ws[i], item.y + item.h, border)
                canvas.save()
                canvas.translate(item.xs[i] + 4f, item.y + 4f)
                canvas.clipRect(0f, 0f, item.ws[i] - 8f, item.h - 8f)
                item.cells[i].draw(canvas)
                canvas.restore()
            }
        }
    }

    /** Page [index] as a picture [widthPx] wide. */
    fun render(index: Int, widthPx: Int): Bitmap {
        val scale = widthPx / pageW
        val bitmap = Bitmap.createBitmap(widthPx, (pageH * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.scale(scale, scale)
        draw(c, index)
        return bitmap
    }

    companion object {
        /** Plain text as a document: one paragraph per line. */
        fun fromText(text: String): DocxDoc =
            DocxDoc(text.split('\n').map { Para(listOf(Run(it.trimEnd('\r'))), spaceAfterPt = 2f) })
    }
}
