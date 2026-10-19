package io.github.harismushtaq12.dragwheel.ops

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import java.io.File

/** One page in the page editor: which original page, and how much extra rotation. */
data class PageEdit(val source: Int, val extraRotation: Int = 0)

object PdfOps {
    @Volatile private var ready = false

    private fun load(context: Context, file: InputFile, password: String = ""): PDDocument {
        if (!ready) {
            PDFBoxResourceLoader.init(context.applicationContext)
            ready = true
        }
        return try {
            Storage.open(context, file).use { PDDocument.load(it, password) }
        } catch (e: InvalidPasswordException) {
            throw IllegalStateException(
                if (password.isEmpty()) "This PDF is locked with a password. Use Unlock first."
                else "That password is not right."
            )
        }
    }

    private fun write(context: Context, doc: PDDocument, name: String, subfolder: String? = null): SavedFile =
        Storage.save(context, name, "application/pdf", subfolder) { doc.save(it) }

    private fun pad(i: Int, total: Int) = (i + 1).toString().padStart(total.toString().length, '0')

    fun pageCount(context: Context, file: InputFile): Int = load(context, file).use { it.numberOfPages }

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

    /** One PDF per chosen page. */
    fun split(context: Context, file: InputFile, pages: (Int) -> List<Int>): List<SavedFile> {
        load(context, file).use { src ->
            val chosen = pages(src.numberOfPages)
            return chosen.map { i ->
                PDDocument().use { single ->
                    single.importPage(src.getPage(i))
                    write(context, single, "page ${pad(i, src.numberOfPages)}.pdf", subfolder = "${file.baseName} pages")
                }
            }
        }
    }

    /** Builds a new PDF from the page editor's list: new order, rotations, deleted pages left out. */
    fun organize(context: Context, file: InputFile, pages: List<PageEdit>, suffix: String = " edited"): SavedFile {
        require(pages.isNotEmpty()) { "Keep at least one page." }
        load(context, file).use { src ->
            PDDocument().use { out ->
                for (p in pages) {
                    val page = out.importPage(src.getPage(p.source))
                    if (p.extraRotation != 0) page.rotation = ((page.rotation + p.extraRotation) % 360 + 360) % 360
                }
                return write(context, out, "${file.baseName}$suffix.pdf")
            }
        }
    }

    fun reverse(context: Context, file: InputFile): SavedFile {
        val count = pageCount(context, file)
        return organize(context, file, (count - 1 downTo 0).map { PageEdit(it) }, " reversed")
    }

    fun rotateAll(context: Context, file: InputFile): SavedFile {
        val count = pageCount(context, file)
        return organize(context, file, (0 until count).map { PageEdit(it, 90) }, " rotated")
    }

    fun extractText(context: Context, file: InputFile): String {
        load(context, file).use { doc ->
            val text = PDFTextStripper().getText(doc).trim()
            if (text.isEmpty()) throw IllegalStateException(
                "No text found. This PDF is probably scanned pictures, so the words are not stored as text."
            )
            return text
        }
    }

    fun lock(context: Context, file: InputFile, password: String): SavedFile {
        require(password.length >= 4) { "Use a password with at least 4 characters." }
        load(context, file).use { doc ->
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            return write(context, doc, "${file.baseName} locked.pdf")
        }
    }

    fun unlock(context: Context, file: InputFile, password: String): SavedFile {
        load(context, file, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            return write(context, doc, "${file.baseName} unlocked.pdf")
        }
    }

    /** Renders chosen pages (or all) to pictures with Android's own PDF renderer. */
    fun toImages(context: Context, file: InputFile, png: Boolean, dpi: Int, pages: (Int) -> List<Int>): List<SavedFile> {
        val local = Storage.localCopy(context, file)
        val ext = if (png) "png" else "jpg"
        val fd = try {
            ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            throw IllegalStateException("Cannot open this PDF.")
        }
        fd.use {
            val renderer = try {
                PdfRenderer(it)
            } catch (e: SecurityException) {
                throw IllegalStateException("This PDF is locked with a password. Use Unlock first.")
            }
            renderer.use { r ->
                val chosen = pages(r.pageCount)
                return chosen.map { i ->
                    r.openPage(i).use { page ->
                        val scale = dpi / 72f
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceIn(1, 8000),
                            (page.height * scale).toInt().coerceIn(1, 8000),
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        Storage.save(context, "page ${pad(i, r.pageCount)}.$ext", subfolder = "${file.baseName} images") { out ->
                            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
                        }.also { bitmap.recycle() }
                    }
                }
            }
        }
    }

    /** Opens a PDF for showing pages on screen. */
    fun openRenderer(file: File): PdfRenderer =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
}
