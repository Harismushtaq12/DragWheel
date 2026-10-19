package io.github.harismushtaq12.dragwheel.ops

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import kotlin.math.max
import kotlin.math.roundToInt

/** What the photo editor asks for. Crop values are 0..1 of the rotated image. */
data class ImageEdit(
    val quarterTurns: Int = 0,
    val flip: Boolean = false,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val brightness: Float = 0f,  // -100..100
    val contrast: Float = 1f,    // 0.5..1.5
    val saturation: Float = 1f,  // 0..2
)

object ImageOps {

    /** Decodes with rotation applied. Software bitmap so we can read and draw it. */
    fun load(context: Context, file: InputFile, maxSide: Int = 4096): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, file.uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val largest = max(info.size.width, info.size.height)
            if (largest > maxSide) {
                val scale = maxSide.toFloat() / largest
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }

    /** Full size when memory allows; falls back to a smaller size on very large photos. */
    fun loadLarge(context: Context, file: InputFile): Bitmap = try {
        load(context, file, maxSide = 8192)
    } catch (e: OutOfMemoryError) {
        load(context, file, maxSide = 4096)
    }

    private fun formatFor(ext: String): Bitmap.CompressFormat = when (ext) {
        "png" -> Bitmap.CompressFormat.PNG
        "webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }

    /** JPG has no transparency: draw on white so it does not turn black. */
    private fun flatten(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        bitmap.recycle()
        return out
    }

    fun save(context: Context, bitmap: Bitmap, baseName: String, ext: String, suffix: String = "", quality: Int = 90): SavedFile {
        val image = if (ext == "jpg") flatten(bitmap) else bitmap
        return Storage.save(context, "$baseName$suffix.$ext", Storage.mimeFor(ext)) { out ->
            image.compress(formatFor(ext), quality.coerceIn(1, 100), out)
        }
    }

    /** Keep the format if Android can write it, otherwise JPG. */
    fun sameOrJpg(file: InputFile): String = when (file.ext) {
        "png" -> "png"
        "webp" -> "webp"
        else -> "jpg"
    }

    fun convert(context: Context, file: InputFile, ext: String, quality: Int = 92): SavedFile =
        save(context, loadLarge(context, file), file.baseName, ext, quality = quality)

    fun compress(context: Context, file: InputFile, quality: Int, maxSide: Int): SavedFile {
        val bitmap = load(context, file, maxSide = maxSide)
        // PNG is lossless, so the smaller choice is a JPG.
        val ext = if (file.ext == "webp") "webp" else "jpg"
        return save(context, bitmap, file.baseName, ext, " compressed", quality = quality)
    }

    fun resize(context: Context, file: InputFile, percent: Int): SavedFile {
        val bitmap = load(context, file, maxSide = 8192)
        val w = max(1, bitmap.width * percent / 100)
        val h = max(1, bitmap.height * percent / 100)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return save(context, scaled, file.baseName, sameOrJpg(file), " ${w}x$h", quality = 90)
    }

    fun rotate(context: Context, file: InputFile): SavedFile {
        val bitmap = loadLarge(context, file)
        val rotated = transform(bitmap, 1, false)
        return save(context, rotated, file.baseName, sameOrJpg(file), " rotated", quality = 92)
    }

    fun grayscale(context: Context, file: InputFile): SavedFile {
        val out = applyColor(loadLarge(context, file), 0f, 1f, 0f)
        return save(context, out, file.baseName, sameOrJpg(file), " bw", quality = 92)
    }

    /** Re-encoding writes only pixels: EXIF, GPS location and camera details are gone. */
    fun clean(context: Context, file: InputFile): SavedFile =
        save(context, loadLarge(context, file), file.baseName, sameOrJpg(file), " clean", quality = 92)

    /** Applies the photo editor's changes to the full picture. */
    fun edit(context: Context, file: InputFile, e: ImageEdit, ext: String, quality: Int): SavedFile {
        var bitmap = transform(loadLarge(context, file), e.quarterTurns, e.flip)
        val l = (e.cropLeft.coerceIn(0f, 1f) * bitmap.width).roundToInt()
        val t = (e.cropTop.coerceIn(0f, 1f) * bitmap.height).roundToInt()
        val r = (e.cropRight.coerceIn(0f, 1f) * bitmap.width).roundToInt()
        val b = (e.cropBottom.coerceIn(0f, 1f) * bitmap.height).roundToInt()
        if (r - l >= 2 && b - t >= 2 && (l > 0 || t > 0 || r < bitmap.width || b < bitmap.height)) {
            val cropped = Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
            if (cropped !== bitmap) bitmap.recycle()
            bitmap = cropped
        }
        if (e.brightness != 0f || e.contrast != 1f || e.saturation != 1f) {
            bitmap = applyColor(bitmap, e.saturation, e.contrast, e.brightness)
        }
        return save(context, bitmap, file.baseName, ext, " edited", quality = quality)
    }

    /** Turns by 90° steps clockwise and optionally mirrors. Recycles the input if it made a copy. */
    fun transform(bitmap: Bitmap, quarterTurns: Int, flip: Boolean): Bitmap {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0 && !flip) return bitmap
        val m = Matrix()
        if (flip) m.postScale(-1f, 1f)
        if (turns != 0) m.postRotate(90f * turns)
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    /** Same numbers as the editor preview, so what you see is what you get. */
    fun colorMatrixValues(saturation: Float, contrast: Float, brightness: Float): FloatArray {
        val sat = ColorMatrix().apply { setSaturation(saturation) }
        val t = brightness + 128f * (1f - contrast)
        val con = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        sat.postConcat(con)
        return sat.array
    }

    private fun applyColor(bitmap: Bitmap, saturation: Float, contrast: Float, brightness: Float): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(colorMatrixValues(saturation, contrast, brightness)))
        }
        Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)
        bitmap.recycle()
        return out
    }

    /** One page per picture. [a4] fits each picture on an A4 page; otherwise the page matches the picture. */
    fun toPdf(context: Context, files: List<InputFile>, a4: Boolean): SavedFile {
        val pdf = PdfDocument()
        try {
            files.forEachIndexed { index, f ->
                val bitmap = load(context, f, maxSide = 3000)
                val pageW: Int
                val pageH: Int
                val scale: Float
                if (a4) {
                    val portrait = bitmap.height >= bitmap.width
                    pageW = if (portrait) 595 else 842
                    pageH = if (portrait) 842 else 595
                    scale = minOf((pageW - 48f) / bitmap.width, (pageH - 48f) / bitmap.height)
                } else {
                    scale = 842f / max(bitmap.width, bitmap.height)
                    pageW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                    pageH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                }
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create())
                val c = page.canvas
                c.drawColor(Color.WHITE)
                c.translate((pageW - bitmap.width * scale) / 2f, (pageH - bitmap.height * scale) / 2f)
                c.scale(scale, scale)
                c.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
                bitmap.recycle()
            }
            val suffix = if (files.size > 1) " (+${files.size - 1})" else ""
            return Storage.save(context, "${files[0].baseName}$suffix.pdf", "application/pdf") { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    /** Windows bitmap (24-bit). Large and simple; opens in every old program. */
    fun toBmp(context: Context, file: InputFile): SavedFile {
        val bmp = flatten(loadLarge(context, file))
        val w = bmp.width
        val h = bmp.height
        val rowSize = (w * 3 + 3) / 4 * 4
        val dataSize = rowSize * h
        return Storage.save(context, "${file.baseName}.bmp", "image/bmp") { out ->
            val header = java.nio.ByteBuffer.allocate(54).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + dataSize).putInt(0).putInt(54)
            header.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24).putInt(0).putInt(dataSize)
                .putInt(2835).putInt(2835).putInt(0).putInt(0)
            out.write(header.array())
            val pixels = IntArray(w)
            val row = ByteArray(rowSize)
            for (y in h - 1 downTo 0) {
                bmp.getPixels(pixels, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val c = pixels[x]
                    row[x * 3] = (c and 0xFF).toByte()
                    row[x * 3 + 1] = (c shr 8 and 0xFF).toByte()
                    row[x * 3 + 2] = (c shr 16 and 0xFF).toByte()
                }
                out.write(row)
            }
        }
    }

    /** Icon file (.ico) up to 256x256, for apps, folders and websites. */
    fun toIco(context: Context, file: InputFile, size: Int): SavedFile {
        val src = load(context, file, maxSide = 2048)
        val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height)
        val icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        Canvas(icon).drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), (size - w) / 2f, (size - h) / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        val png = java.io.ByteArrayOutputStream().also { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return Storage.save(context, "${file.baseName}.ico", "image/x-icon") { out ->
            val b = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.putShort(0).putShort(1).putShort(1)
            b.put((if (size >= 256) 0 else size).toByte()).put((if (size >= 256) 0 else size).toByte()).put(0).put(0)
            b.putShort(1).putShort(32).putInt(png.size).putInt(22)
            out.write(b.array())
            out.write(png)
        }
    }

    /** HEIC: about half the size of JPG at the same look. Needs the phone's HEVC encoder. */
    fun toHeic(context: Context, file: InputFile, quality: Int): SavedFile {
        var bmp = load(context, file, maxSide = 4096)
        // Video encoders want even sizes.
        val w = bmp.width - bmp.width % 2
        val h = bmp.height - bmp.height % 2
        if (w != bmp.width || h != bmp.height) bmp = Bitmap.createBitmap(bmp, 0, 0, w.coerceAtLeast(2), h.coerceAtLeast(2))
        val temp = java.io.File(context.cacheDir, "heic-${System.nanoTime()}.heic")
        try {
            val writer = androidx.heifwriter.HeifWriter.Builder(temp.absolutePath, bmp.width, bmp.height, androidx.heifwriter.HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(quality.coerceIn(1, 100))
                .build()
            try {
                writer.start()
                writer.addBitmap(bmp)
                writer.stop(20_000)
            } finally {
                writer.close()
            }
            if (!temp.exists() || temp.length() == 0L) throw IllegalStateException("This phone cannot make HEIC files.")
            return Storage.saveFile(context, temp, "${file.baseName}.heic", "image/heic")
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("This phone cannot make HEIC files. Try JPG or WebP.")
        } finally {
            temp.delete()
        }
    }

    /** Pictures in a Word file, one after another. */
    fun toDocx(context: Context, files: List<InputFile>): SavedFile {
        val w = io.github.harismushtaq12.dragwheel.ops.doc.DocxWriter()
        val maxW = w.pageWidthPt - 2 * w.marginPt
        val maxH = w.pageHeightPt - 2 * w.marginPt - 20
        for (f in files) {
            val bmp = load(context, f, maxSide = 2400)
            val png = bmp.hasAlpha()
            val bytes = java.io.ByteArrayOutputStream().also {
                bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)
            }.toByteArray()
            val s = minOf(1f, maxW / (bmp.width * 0.75f), maxH / (bmp.height * 0.75f))
            w.image(bytes, if (png) "png" else "jpeg", bmp.width * 0.75f * s, bmp.height * 0.75f * s)
            bmp.recycle()
        }
        val suffix = if (files.size > 1) " (+${files.size - 1})" else ""
        return DocOps.writeDocx(context, "${files[0].baseName}$suffix.docx", w)
    }

    fun readQr(context: Context, file: InputFile): String {
        val bitmap = load(context, file, maxSide = 2000)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
        return try {
            MultiFormatReader().decode(binary, mapOf(DecodeHintType.TRY_HARDER to true)).text
        } catch (e: NotFoundException) {
            throw IllegalStateException("No QR code or barcode found in this picture.")
        }
    }
}
