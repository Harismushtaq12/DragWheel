package io.github.harismushtaq12.dragwheel.ops

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.Storage
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Reads printed words from pictures and scanned PDFs. Runs on the phone (Latin letters). */
object OcrOps {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Must not run on the main thread. */
    fun read(bitmap: Bitmap): String {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        return result.textBlocks.joinToString("\n\n") { it.text }
    }

    fun readImage(context: Context, file: InputFile): String {
        val bmp = ImageOps.load(context, file, maxSide = 3000)
        val text = read(bmp).trim()
        if (text.isEmpty()) throw IllegalStateException("No words were found in this picture.")
        return text
    }

    suspend fun readPdf(context: Context, file: InputFile): String {
        val local = Storage.localCopy(context, file)
        val sb = StringBuilder()
        PdfOps.openRenderer(local).use { r ->
            for (i in 0 until r.pageCount) {
                coroutineContext.ensureActive()
                val bmp = PdfEditOps.renderForOcr(context, file, i, r)
                val text = read(bmp).trim()
                bmp.recycle()
                if (r.pageCount > 1) sb.append("— Page ${i + 1} —\n")
                sb.append(text).append("\n\n")
            }
        }
        val all = sb.toString().trim()
        if (all.replace(Regex("— Page \\d+ —"), "").isBlank()) throw IllegalStateException("No words were found in this PDF.")
        return all
    }
}
