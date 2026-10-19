package io.github.harismushtaq12.dragwheel.ops

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import java.io.IOException

object PdfOps {
    @Volatile private var ready = false

    private fun load(context: Context, file: InputFile): PDDocument {
        if (!ready) {
            PDFBoxResourceLoader.init(context.applicationContext)
            ready = true
        }
        return Storage.open(context, file).use { PDDocument.load(it) }
    }

    private fun write(context: Context, doc: PDDocument, name: String, subfolder: String? = null): SavedFile =
        Storage.save(context, name, "application/pdf", subfolder) { doc.save(it) }

    private fun pad(i: Int, total: Int) = (i + 1).toString().padStart(total.toString().length, '0')

    fun merge(context: Context, files: List<InputFile>): SavedFile {
        val sources = files.map { load(context, it) }
        val out = PDDocument()
        try {
            sources.forEach { src -> for (page in src.pages) out.importPage(page) }
            return write(context, out, "${files[0].baseName} merged.pdf")
        } finally {
            out.close()
            sources.forEach { it.close() }
        }
    }

    fun split(context: Context, file: InputFile): List<SavedFile> {
        val src = load(context, file)
        try {
            return (0 until src.numberOfPages).map { i ->
                PDDocument().use { single ->
                    single.importPage(src.getPage(i))
                    write(context, single, "page ${pad(i, src.numberOfPages)}.pdf", subfolder = "${file.baseName} pages")
                }
            }
        } finally {
            src.close()
        }
    }

    fun reverse(context: Context, file: InputFile): SavedFile {
        val src = load(context, file)
        val out = PDDocument()
        try {
            for (i in src.numberOfPages - 1 downTo 0) out.importPage(src.getPage(i))
            return write(context, out, "${file.baseName} reversed.pdf")
        } finally {
            out.close()
            src.close()
        }
    }

    fun rotate(context: Context, file: InputFile): SavedFile {
        load(context, file).use { doc ->
            for (page in doc.pages) page.rotation = (page.rotation + 90) % 360
            return write(context, doc, "${file.baseName} rotated.pdf")
        }
    }

    /** Renders every page with Android's own PDF renderer, at about 150 dpi. */
    fun render(context: Context, file: InputFile, png: Boolean): List<SavedFile> {
        val descriptor = context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: throw IOException("Cannot open ${file.name}")
        val ext = if (png) "png" else "jpg"
        descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                return (0 until renderer.pageCount).map { i ->
                    renderer.openPage(i).use { page ->
                        val scale = 150f / 72f
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        Storage.save(context, "page ${pad(i, renderer.pageCount)}.$ext", subfolder = "${file.baseName} images") {
                            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)
                        }.also { bitmap.recycle() }
                    }
                }
            }
        }
    }
}
