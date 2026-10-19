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

object ImageOps {

    /** Decodes with rotation applied. Software bitmap so we can read and draw it. */
    private fun load(context: Context, file: InputFile, maxSide: Int = 4096): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, file.uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val largest = max(info.size.width, info.size.height)
            if (largest > maxSide) {
                val scale = maxSide.toFloat() / largest
                decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            }
        }
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

    private fun save(context: Context, bitmap: Bitmap, file: InputFile, ext: String, suffix: String = "", quality: Int = 90): SavedFile {
        val image = if (ext == "jpg") flatten(bitmap) else bitmap
        return Storage.save(context, "${file.baseName}$suffix.$ext") { out ->
            image.compress(formatFor(ext), quality, out)
        }
    }

    /** Keep the format if Android can write it, otherwise JPG. */
    private fun sameOrJpg(file: InputFile): String = when (file.ext) {
        "png" -> "png"
        "webp" -> "webp"
        else -> "jpg"
    }

    fun convert(context: Context, file: InputFile, ext: String): SavedFile =
        save(context, load(context, file), file, ext, quality = if (ext == "webp") 85 else 92)

    fun compress(context: Context, file: InputFile): SavedFile {
        val bitmap = load(context, file, maxSide = 2560)
        // PNG is lossless, so the smaller choice is a JPG.
        val ext = if (file.ext == "webp") "webp" else "jpg"
        return save(context, bitmap, file, ext, " compressed", quality = 70)
    }

    fun halfSize(context: Context, file: InputFile): SavedFile {
        val bitmap = load(context, file)
        val half = Bitmap.createScaledBitmap(bitmap, max(1, bitmap.width / 2), max(1, bitmap.height / 2), true)
        if (half !== bitmap) bitmap.recycle()
        return save(context, half, file, sameOrJpg(file), " small", quality = 88)
    }

    fun rotate(context: Context, file: InputFile): SavedFile {
        val bitmap = load(context, file)
        val m = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (rotated !== bitmap) bitmap.recycle()
        return save(context, rotated, file, sameOrJpg(file), " rotated", quality = 92)
    }

    fun grayscale(context: Context, file: InputFile): SavedFile {
        val bitmap = load(context, file)
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)
        bitmap.recycle()
        return save(context, out, file, sameOrJpg(file), " bw", quality = 92)
    }

    /** Re-encoding writes only pixels: EXIF, GPS location and camera details are gone. */
    fun clean(context: Context, file: InputFile): SavedFile =
        save(context, load(context, file), file, sameOrJpg(file), " clean", quality = 92)

    fun toPdf(context: Context, files: List<InputFile>): SavedFile {
        val pdf = PdfDocument()
        try {
            files.forEachIndexed { index, f ->
                val bitmap = load(context, f, maxSide = 3000)
                // Page size in points; long side fitted to A4 height (842 pt).
                val scale = 842f / max(bitmap.width, bitmap.height)
                val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.scale(scale, scale)
                page.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
                bitmap.recycle()
            }
            val suffix = if (files.size > 1) " (+${files.size - 1})" else ""
            return Storage.save(context, "${files[0].baseName}$suffix.pdf", "application/pdf") { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    fun readQr(context: Context, file: InputFile): String {
        val bitmap = load(context, file, maxSide = 2000)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
        return try {
            MultiFormatReader().decode(binary, mapOf(DecodeHintType.TRY_HARDER to true)).text
        } catch (e: NotFoundException) {
            throw IllegalStateException("No QR code or barcode found in this image.")
        }
    }
}
