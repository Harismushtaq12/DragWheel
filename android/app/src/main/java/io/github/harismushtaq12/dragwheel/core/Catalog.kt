package io.github.harismushtaq12.dragwheel.core

import io.github.harismushtaq12.dragwheel.ops.ArchiveOps
import io.github.harismushtaq12.dragwheel.ops.ImageOps
import io.github.harismushtaq12.dragwheel.ops.MediaOps
import io.github.harismushtaq12.dragwheel.ops.PdfOps

/**
 * Decides what appears on the wheel for the chosen files.
 * To add a feature, add a WheelAction to the right list.
 */
object Catalog {
    private const val MAX_ITEMS = 8

    private enum class Kind { IMAGE, AUDIO, VIDEO, PDF, ZIP, OTHER }

    private fun kindOf(f: InputFile): Kind = when {
        f.mime.startsWith("image/") -> Kind.IMAGE
        f.mime.startsWith("video/") -> Kind.VIDEO
        f.mime.startsWith("audio/") -> Kind.AUDIO
        f.mime == "application/pdf" || f.ext == "pdf" -> Kind.PDF
        f.mime == "application/zip" || f.ext == "zip" -> Kind.ZIP
        f.ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif") -> Kind.IMAGE
        f.ext in setOf("mp4", "mov", "mkv", "webm", "3gp", "m4v") -> Kind.VIDEO
        f.ext in setOf("mp3", "m4a", "wav", "flac", "ogg", "opus", "aac", "amr") -> Kind.AUDIO
        else -> Kind.OTHER
    }

    fun forFiles(files: List<InputFile>, tools: Boolean): List<WheelAction> {
        if (files.isEmpty()) return emptyList()
        val kinds = files.map(::kindOf).distinct()
        val kind = if (kinds.size == 1) kinds[0] else Kind.OTHER

        val list: MutableList<WheelAction> = when (kind) {
            Kind.IMAGE -> if (tools) imageTools() else imageConversions(files)
            Kind.VIDEO -> if (tools) videoTools() else videoConversions()
            Kind.AUDIO -> if (tools) mutableListOf() else audioConversions()
            Kind.PDF -> if (tools) pdfTools() else pdfConversions(files.size)
            Kind.ZIP -> if (tools) mutableListOf() else mutableListOf(unzip())
            Kind.OTHER -> if (tools) mutableListOf() else mutableListOf(zip())
        }
        if (tools) {
            if (kind != Kind.ZIP && list.none { it.title == "Zip" }) list += zip()
            list += hash()
        }
        return list.take(MAX_ITEMS)
    }

    private fun perFile(
        title: String, hint: String, done: String,
        work: suspend (android.content.Context, InputFile) -> List<SavedFile>,
    ) = WheelAction(title, hint, done) { ctx, files ->
        ActionResult(outputs = files.flatMap { work(ctx, it) })
    }

    // ---------- Images ----------

    private fun imageConversions(files: List<InputFile>): MutableList<WheelAction> {
        val ext = files[0].ext.let { if (it == "jpeg") "jpg" else it }
        val list = listOf(
            Triple("jpg", "JPG", "photo"),
            Triple("png", "PNG", "lossless"),
            Triple("webp", "WebP", "for the web"),
        ).filter { it.first != ext }
            .map { (e, title, hint) -> perFile(title, hint, "Converted") { c, f -> listOf(ImageOps.convert(c, f, e)) } }
            .toMutableList()
        list += WheelAction("PDF", if (files.size > 1) "${files.size} pages" else "one page", "PDF ready") { c, fs ->
            ActionResult(outputs = listOf(ImageOps.toPdf(c, fs)))
        }
        return list
    }

    private fun imageTools(): MutableList<WheelAction> = mutableListOf(
        perFile("Compress", "smaller file", "Compressed") { c, f -> listOf(ImageOps.compress(c, f)) },
        perFile("Half size", "50% width", "Resized") { c, f -> listOf(ImageOps.halfSize(c, f)) },
        perFile("Rotate", "90° right", "Rotated") { c, f -> listOf(ImageOps.rotate(c, f)) },
        perFile("Grayscale", "black & white", "Done") { c, f -> listOf(ImageOps.grayscale(c, f)) },
        perFile("Clean", "no metadata", "Metadata removed") { c, f -> listOf(ImageOps.clean(c, f)) },
        WheelAction("Read QR", "copy text", "Copied") { c, fs ->
            val text = ImageOps.readQr(c, fs[0])
            ActionResult(clipboard = text, message = text)
        },
    )

    // ---------- Video and audio ----------

    private fun videoConversions(): MutableList<WheelAction> = mutableListOf(
        perFile("MP4", "H.264", "Converted") { c, f -> listOf(MediaOps.toMp4(c, f)) },
        perFile("Audio", "M4A only", "Audio saved") { c, f -> listOf(MediaOps.audioOnly(c, f)) },
        perFile("Frame", "first frame", "Frame saved") { c, f -> listOf(MediaOps.firstFrame(c, f)) },
    )

    private fun videoTools(): MutableList<WheelAction> = mutableListOf(
        perFile("Compress", "720p", "Compressed") { c, f -> listOf(MediaOps.compress(c, f)) },
        perFile("Mute", "remove sound", "Muted") { c, f -> listOf(MediaOps.mute(c, f)) },
        perFile("Rotate", "90° right", "Rotated") { c, f -> listOf(MediaOps.rotate(c, f)) },
    )

    private fun audioConversions(): MutableList<WheelAction> = mutableListOf(
        perFile("M4A", "AAC", "Converted") { c, f -> listOf(MediaOps.audioOnly(c, f)) },
    )

    // ---------- PDF ----------

    private fun pdfConversions(count: Int): MutableList<WheelAction> {
        val list = mutableListOf(
            perFile("PNG", "every page", "Pages saved") { c, f -> PdfOps.render(c, f, png = true) },
            perFile("JPG", "every page", "Pages saved") { c, f -> PdfOps.render(c, f, png = false) },
        )
        if (count > 1) list.add(0, WheelAction("Merge", "$count PDFs", "Merged") { c, fs ->
            ActionResult(outputs = listOf(PdfOps.merge(c, fs)))
        })
        return list
    }

    private fun pdfTools(): MutableList<WheelAction> = mutableListOf(
        perFile("Split", "one per page", "Split") { c, f -> PdfOps.split(c, f) },
        perFile("Reverse", "page order", "Reversed") { c, f -> listOf(PdfOps.reverse(c, f)) },
        perFile("Rotate", "90° right", "Rotated") { c, f -> listOf(PdfOps.rotate(c, f)) },
    )

    // ---------- Any file ----------

    private fun zip() = WheelAction("Zip", "compress", "Zipped") { c, fs ->
        ActionResult(outputs = listOf(ArchiveOps.zip(c, fs)))
    }

    private fun unzip() = perFile("Unzip", "extract", "Extracted") { c, f -> ArchiveOps.unzip(c, f) }

    private fun hash() = WheelAction("SHA-256", "copy checksum", "Copied") { c, fs ->
        val text = fs.joinToString("\n") { f ->
            val h = ArchiveOps.sha256(c, f)
            if (fs.size == 1) h else "$h  ${f.name}"
        }
        ActionResult(clipboard = text, message = text)
    }
}
