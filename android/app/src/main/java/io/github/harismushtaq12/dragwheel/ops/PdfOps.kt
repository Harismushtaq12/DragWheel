package io.github.harismushtaq12.dragwheel.ops

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One page in the page editor: which original page, and how much extra rotation. */
data class PageEdit(val source: Int, val extraRotation: Int = 0)

object PdfOps {
    @Volatile private var ready = false

    /** Opens a PDF from a private copy, keeping big documents on disk instead of in memory. */
    internal fun load(context: Context, file: InputFile, password: String = ""): PDDocument {
        if (!ready) {
            PDFBoxResourceLoader.init(context.applicationContext)
            ready = true
        }
        return try {
            PDDocument.load(Storage.localCopy(context, file), password, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw IllegalStateException(
                if (password.isEmpty()) "This PDF is locked with a password. Use Tools, then Unlock."
                else "That password is not right."
            )
        }
    }

    internal fun write(context: Context, doc: PDDocument, name: String, subfolder: String? = null): SavedFile =
        Storage.save(context, name, "application/pdf", subfolder) { doc.save(it) }

    internal fun pad(i: Int, total: Int) = (i + 1).toString().padStart(total.toString().length, '0')

    /**
     * Pages can get fonts, pictures, size and rotation from their parent in the page tree.
     * Copy those onto the page itself, so it keeps them after it is moved or copied.
     */
    private fun PDPage.pinInherited() {
        resources = resources
        mediaBox = mediaBox
        cropBox = cropBox
        rotation = rotation
    }

    fun merge(context: Context, files: List<InputFile>): SavedFile {
        val out = PDDocument()
        val opened = mutableListOf<PDDocument>()
        try {
            for (f in files) {
                val src = load(context, f)
                opened += src
                for (page in src.pages) {
                    page.pinInherited()
                    out.importPage(page)
                }
            }
            return write(context, out, "${files[0].baseName} merged.pdf")
        } finally {
            out.close()
            opened.forEach { it.close() }
        }
    }

    /** One PDF per chosen page. */
    fun split(context: Context, file: InputFile, pages: (Int) -> List<Int>): List<SavedFile> {
        load(context, file).use { src ->
            val chosen = pages(src.numberOfPages)
            return chosen.map { i ->
                PDDocument().use { single ->
                    val page = src.getPage(i)
                    page.pinInherited()
                    single.importPage(page)
                    write(context, single, "page ${pad(i, src.numberOfPages)}.pdf", subfolder = "${file.baseName} pages")
                }
            }
        }
    }

    /**
     * Rebuilds the page order inside the same document: new order, rotations, deleted pages removed.
     * Working in place keeps fonts, links and form fields.
     */
    fun organize(context: Context, file: InputFile, pages: List<PageEdit>, suffix: String = " edited"): SavedFile {
        require(pages.isNotEmpty()) { "Keep at least one page." }
        load(context, file).use { doc ->
            val all = doc.pages.toList()
            all.forEach { it.pinInherited() }
            for (p in all) doc.pages.remove(p)
            for (e in pages) {
                val page = all[e.source]
                if (e.extraRotation != 0) page.rotation = ((page.rotation + e.extraRotation) % 360 + 360) % 360
                doc.pages.add(page)
            }
            return write(context, doc, "${file.baseName}$suffix.pdf")
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

    fun pageCount(context: Context, file: InputFile): Int = try {
        openRenderer(Storage.localCopy(context, file)).use { it.pageCount }
    } catch (e: SecurityException) {
        load(context, file).use { it.numberOfPages }
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
    fun toImages(context: Context, file: InputFile, png: Boolean, dpi: Int, pages: (Int) -> List<PageEdit>): List<SavedFile> {
        val local = Storage.localCopy(context, file)
        val ext = if (png) "png" else "jpg"
        val renderer = try {
            openRenderer(local)
        } catch (e: SecurityException) {
            throw IllegalStateException("This PDF is locked with a password. Use Tools, then Unlock.")
        }
        renderer.use { r ->
            val chosen = pages(r.pageCount)
            return chosen.map { p ->
                r.openPage(p.source).use { page ->
                    // Same scale for both sides (no stretching), and at most ~40 megapixels.
                    val area = page.width.toFloat() * page.height
                    val s = min(dpi / 72f, min(sqrt(40_000_000f / area), 8000f / max(page.width, page.height)))
                    var bitmap = Bitmap.createBitmap(
                        (page.width * s).toInt().coerceAtLeast(1),
                        (page.height * s).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    if (p.extraRotation != 0) bitmap = ImageOps.transform(bitmap, p.extraRotation / 90, false)
                    Storage.save(context, "page ${pad(p.source, r.pageCount)}.$ext", subfolder = "${file.baseName} images") { out ->
                        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
                    }.also { bitmap.recycle() }
                }
            }
        }
    }

    /** Opens a PDF for showing pages on screen. Throws SecurityException for locked PDFs. */
    fun openRenderer(file: File): PdfRenderer =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
}
