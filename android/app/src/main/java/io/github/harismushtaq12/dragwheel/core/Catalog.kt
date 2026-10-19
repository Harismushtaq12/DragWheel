package io.github.harismushtaq12.dragwheel.core

import android.content.Context
import io.github.harismushtaq12.dragwheel.ops.ArchiveOps
import io.github.harismushtaq12.dragwheel.ops.DocOps
import io.github.harismushtaq12.dragwheel.ops.ImageOps
import io.github.harismushtaq12.dragwheel.ops.MediaOps
import io.github.harismushtaq12.dragwheel.ops.PageEdit
import io.github.harismushtaq12.dragwheel.ops.PdfOps
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/**
 * Decides what appears on the wheel for the chosen files.
 * Every action explains itself in plain words, so people know what will happen.
 * To add a feature, add a WheelAction to the right list.
 */
object Catalog {
    private const val MAX_ITEMS = 8

    enum class Kind { IMAGE, AUDIO, VIDEO, PDF, DOC, ZIP, OTHER }

    fun kindOf(f: InputFile): Kind = when {
        f.mime.startsWith("image/") -> Kind.IMAGE
        f.mime.startsWith("video/") -> Kind.VIDEO
        f.mime.startsWith("audio/") -> Kind.AUDIO
        f.mime == "application/pdf" || f.ext == "pdf" -> Kind.PDF
        f.mime == "application/zip" || f.ext == "zip" -> Kind.ZIP
        DocOps.isDocument(f) -> Kind.DOC
        f.ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif") -> Kind.IMAGE
        f.ext in setOf("mp4", "mov", "mkv", "webm", "3gp", "m4v") -> Kind.VIDEO
        f.ext in setOf("mp3", "m4a", "wav", "flac", "ogg", "opus", "aac", "amr") -> Kind.AUDIO
        else -> Kind.OTHER
    }

    fun kindOf(files: List<InputFile>): Kind {
        val kinds = files.map(::kindOf).distinct()
        return if (kinds.size == 1) kinds[0] else Kind.OTHER
    }

    fun forFiles(files: List<InputFile>, tools: Boolean): List<WheelAction> {
        if (files.isEmpty()) return emptyList()
        val kind = kindOf(files)
        val single = files.size == 1

        val list: MutableList<WheelAction> = when (kind) {
            Kind.IMAGE -> if (tools) imageTools(single) else imageConvert(files.size, single)
            Kind.VIDEO -> if (tools) videoTools(single) else videoConvert(single)
            Kind.AUDIO -> if (tools) audioTools(single) else audioConvert(single)
            Kind.PDF -> if (tools) pdfTools(single) else pdfConvert(files.size, single)
            Kind.DOC -> if (tools) mutableListOf() else docConvert(single)
            Kind.ZIP -> if (tools) mutableListOf() else zipConvert(single)
            Kind.OTHER -> if (tools) mutableListOf() else mutableListOf(zip(files.size))
        }
        if (tools) {
            if (kind != Kind.ZIP && kind != Kind.OTHER) list += zip(files.size)
            if (kind == Kind.OTHER) list += zip(files.size)
            list += hash()
        }
        return list.take(MAX_ITEMS)
    }

    // ---------- Shared options ----------

    private fun quality(default: Float = 85f) = OptionSpec.Slider(
        "quality", "Quality", 30f, 100f, 13, default,
    ) { v -> "${v.roundToInt()}%" + if (v >= 90) " (best)" else if (v < 60) " (smallest file)" else "" }

    private val pagesOption = OptionSpec.Text(
        "pages", "Pages", "All pages",
        help = "Leave empty for every page, or type pages like 1-3, 5",
    )

    private fun perFile(
        title: String, hint: String, description: String, button: String, done: String,
        options: List<OptionSpec> = emptyList(),
        work: suspend (Context, InputFile, Options) -> List<SavedFile>,
    ) = WheelAction(title, hint, description, button, done, options) { ctx, files, opts ->
        ActionResult(outputs = files.flatMap {
            coroutineContext.ensureActive() // stop between files if the user cancelled
            work(ctx, it, opts)
        })
    }

    private fun editor(title: String, hint: String, description: String, editor: Editor) =
        WheelAction(title, hint, description, "Open", "", editor = editor)

    private fun view(description: String) =
        editor("View", "look inside", description, Editor.VIEW)

    // ---------- Images ----------

    private fun imageConvert(count: Int, single: Boolean): MutableList<WheelAction> {
        val list = mutableListOf<WheelAction>()
        if (single) {
            list += view("Opens the picture full screen. Pinch to zoom.")
            list += editor(
                "Edit", "crop, rotate, colour",
                "Opens the photo editor: crop, rotate, flip, and change brightness, contrast and colour. You see a preview before anything is saved.",
                Editor.IMAGE,
            )
        }
        list += perFile(
            "JPG", "photo", "Makes a JPG copy. JPG works everywhere and keeps files small. Transparent areas become white.",
            "Convert to JPG", "Converted to JPG", listOf(quality(92f)),
        ) { c, f, o -> listOf(ImageOps.convert(c, f, "jpg", o.num("quality", 92f).roundToInt())) }
        list += perFile(
            "PNG", "lossless", "Makes a PNG copy. PNG keeps full detail and supports transparency, but files are larger. Extremely large photos may be reduced to fit the phone's memory.",
            "Convert to PNG", "Converted to PNG",
        ) { c, f, _ -> listOf(ImageOps.convert(c, f, "png")) }
        list += perFile(
            "WebP", "for the web", "Makes a WebP copy. WebP looks like JPG but is usually about a third smaller. Good for websites.",
            "Convert to WebP", "Converted to WebP", listOf(quality(85f)),
        ) { c, f, o -> listOf(ImageOps.convert(c, f, "webp", o.num("quality", 85f).roundToInt())) }
        list += WheelAction(
            "PDF", if (count > 1) "$count pages" else "one page",
            if (count > 1) "Puts all $count pictures into one PDF, one picture per page, in the order you chose them."
            else "Puts the picture on a PDF page.",
            "Make PDF", "PDF ready",
            listOf(OptionSpec.Choice("page", "Page size", listOf("a4" to "A4 paper", "fit" to "Same as picture"), "a4")),
        ) { c, fs, o -> ActionResult(outputs = listOf(ImageOps.toPdf(c, fs, a4 = o.str("page", "a4") == "a4"))) }
        return list
    }

    private fun imageTools(single: Boolean): MutableList<WheelAction> {
        val list = mutableListOf(
            perFile(
                "Compress", "smaller file",
                "Makes the file smaller so it is quicker to send. Lower quality means a smaller file. Very large photos are also scaled down.",
                "Compress", "Compressed",
                listOf(
                    quality(70f),
                    OptionSpec.Choice("max", "Largest side", listOf("4096" to "Full", "2560" to "2560 px", "1600" to "1600 px", "1080" to "1080 px"), "2560"),
                ),
            ) { c, f, o -> listOf(ImageOps.compress(c, f, o.num("quality", 70f).roundToInt(), o.str("max", "2560").toInt())) },
            perFile(
                "Resize", "change size", "Makes the picture bigger or smaller by a percentage. The shape stays the same.",
                "Resize", "Resized",
                listOf(OptionSpec.Slider("percent", "New size", 10f, 200f, 37, 50f) { v -> "${v.roundToInt()}%" }),
            ) { c, f, o -> listOf(ImageOps.resize(c, f, o.num("percent", 50f).roundToInt())) },
            perFile(
                "Rotate", "90° right", "Turns the picture a quarter turn to the right.", "Rotate", "Rotated",
            ) { c, f, _ -> listOf(ImageOps.rotate(c, f)) },
            perFile(
                "Grayscale", "black & white", "Removes all colour and keeps only shades of grey.", "Make black & white", "Done",
            ) { c, f, _ -> listOf(ImageOps.grayscale(c, f)) },
            perFile(
                "Clean", "no metadata",
                "Removes hidden details stored in the photo, like GPS location, date and camera model. Good before posting online.",
                "Remove details", "Hidden details removed",
            ) { c, f, _ -> listOf(ImageOps.clean(c, f)) },
        )
        if (single) list += WheelAction(
            "Read QR", "copy text", "Looks for a QR code or barcode in the picture and copies what it says.",
            "Read code", "Code found",
        ) { c, fs, _ ->
            val text = ImageOps.readQr(c, fs[0])
            ActionResult(clipboard = text, message = text)
        }
        return list
    }

    // ---------- Video and audio ----------

    private fun videoConvert(single: Boolean): MutableList<WheelAction> {
        val list = mutableListOf<WheelAction>()
        if (single) list += view("Plays the video so you can check it first.")
        list += perFile(
            "MP4", "plays anywhere", "Makes an MP4 (H.264) copy. MP4 plays on almost every phone, computer and website.",
            "Convert to MP4", "Converted to MP4",
        ) { c, f, _ -> listOf(MediaOps.toMp4(c, f)) }
        list += perFile(
            "Audio", "sound only", "Saves only the sound as an M4A file. Good for songs, lectures and voice notes.",
            "Save sound", "Sound saved",
        ) { c, f, _ -> listOf(MediaOps.audioOnly(c, f)) }
        list += perFile(
            "Picture", "first frame", "Saves the first frame of the video as a PNG picture.",
            "Save picture", "Picture saved",
        ) { c, f, _ -> listOf(MediaOps.firstFrame(c, f)) }
        return list
    }

    private fun videoTools(single: Boolean): MutableList<WheelAction> {
        val list = mutableListOf<WheelAction>()
        if (single) list += perFile(
            "Trim", "cut start/end", "Keeps only the part between the start and end you choose. The rest is cut away.",
            "Trim video", "Trimmed", listOf(OptionSpec.Range("range", "Keep")),
        ) { c, f, o -> listOf(MediaOps.trim(c, f, o.range("range") ?: throw IllegalArgumentException("Choose start and end"), isAudio = false)) }
        list += perFile(
            "Compress", "smaller video",
            "Makes the video file smaller for sending. It is saved again at a lower bitrate, and big videos are scaled down to the size you choose. Small videos are never made bigger.",
            "Compress video", "Compressed",
            listOf(OptionSpec.Choice("height", "Size", listOf("1080" to "1080p (sharp)", "720" to "720p (balanced)", "480" to "480p (smallest)"), "720")),
        ) { c, f, o -> listOf(MediaOps.compress(c, f, o.str("height", "720").toInt())) }
        list += perFile(
            "Mute", "remove sound", "Makes a copy of the video with no sound.", "Remove sound", "Muted",
        ) { c, f, _ -> listOf(MediaOps.mute(c, f)) }
        list += perFile(
            "Rotate", "90° right", "Turns the video a quarter turn to the right. Useful for sideways phone videos.",
            "Rotate video", "Rotated",
        ) { c, f, _ -> listOf(MediaOps.rotate(c, f)) }
        return list
    }

    private fun audioConvert(single: Boolean): MutableList<WheelAction> {
        val list = mutableListOf<WheelAction>()
        if (single) list += view("Plays the audio so you can check it first.")
        list += perFile(
            "M4A", "AAC audio", "Makes an M4A (AAC) copy. It sounds like MP3 and plays on all phones and computers.",
            "Convert to M4A", "Converted to M4A",
        ) { c, f, _ -> listOf(MediaOps.audioOnly(c, f)) }
        return list
    }

    private fun audioTools(single: Boolean): MutableList<WheelAction> {
        val list = mutableListOf<WheelAction>()
        if (single) list += perFile(
            "Trim", "cut start/end", "Keeps only the part between the start and end you choose. Saved as M4A.",
            "Trim audio", "Trimmed", listOf(OptionSpec.Range("range", "Keep")),
        ) { c, f, o -> listOf(MediaOps.trim(c, f, o.range("range") ?: throw IllegalArgumentException("Choose start and end"), isAudio = true)) }
        return list
    }

    // ---------- PDF ----------

    private fun pdfConvert(count: Int, single: Boolean): MutableList<WheelAction> {
        val list = mutableListOf<WheelAction>()
        if (count > 1) list += WheelAction(
            "Merge", "$count into one", "Joins all $count PDFs into one PDF, in the order you chose them.",
            "Merge PDFs", "Merged",
        ) { c, fs, _ -> ActionResult(outputs = listOf(PdfOps.merge(c, fs))) }
        if (single) {
            list += view("Shows every page so you can read the PDF first.")
            list += editor(
                "Pages", "edit pages",
                "Shows all pages. Pick pages to rotate, delete or move, then save a new PDF, or turn the pages you pick into pictures.",
                Editor.PDF_PAGES,
            )
        }
        list += perFile(
            "Pictures", "pages to images",
            "Turns pages into pictures, one picture per page. Choose every page or only some.",
            "Make pictures", "Pictures ready",
            listOf(
                OptionSpec.Choice("format", "Picture type", listOf("jpg" to "JPG (smaller)", "png" to "PNG (sharper)"), "jpg"),
                OptionSpec.Choice("dpi", "Sharpness", listOf("100" to "Screen", "150" to "Normal", "300" to "Print"), "150"),
                pagesOption,
            ),
        ) { c, f, o ->
            PdfOps.toImages(c, f, png = o.str("format", "jpg") == "png", dpi = o.str("dpi", "150").toInt()) { n ->
                parsePages(o.str("pages"), n).map { PageEdit(it) }
            }
        }
        if (single) list += WheelAction(
            "Text", "copy the words",
            "Takes the words out of the PDF and opens them in the text editor. You can edit them and save as Word, TXT or PDF. Scanned PDFs have no words to take.",
            "Get text", "",
        ) { c, fs, _ -> ActionResult(text = TextDoc(fs[0].baseName, PdfOps.extractText(c, fs[0]))) }
        return list
    }

    private fun pdfTools(single: Boolean): MutableList<WheelAction> = mutableListOf(
        perFile(
            "Split", "page by page", "Saves pages as separate PDF files, one file per page. Choose every page or only some.",
            "Split PDF", "Split", listOf(pagesOption),
        ) { c, f, o -> PdfOps.split(c, f) { n -> parsePages(o.str("pages"), n) } },
        perFile(
            "Rotate", "all pages", "Turns every page a quarter turn to the right. To turn only some pages, use Pages instead.",
            "Rotate pages", "Rotated",
        ) { c, f, _ -> listOf(PdfOps.rotateAll(c, f)) },
        perFile(
            "Reverse", "page order", "Puts the pages in the opposite order, last page first.", "Reverse order", "Reversed",
        ) { c, f, _ -> listOf(PdfOps.reverse(c, f)) },
        perFile(
            "Lock", "add password",
            "Adds a password. Anyone opening the PDF must type it. Do not forget it: it cannot be recovered.",
            "Lock PDF", "Locked",
            listOf(OptionSpec.Text("password", "New password", "At least 4 characters", password = true, required = true)),
        ) { c, f, o -> listOf(PdfOps.lock(c, f, o.str("password"))) },
        perFile(
            "Unlock", "remove password",
            "Removes the password from a PDF you are allowed to open. You need to know the current password.",
            "Unlock PDF", "Unlocked",
            listOf(OptionSpec.Text("password", "Current password", "Password", password = true, required = true)),
        ) { c, f, o -> listOf(PdfOps.unlock(c, f, o.str("password"))) },
    )

    // ---------- Documents ----------

    private fun docConvert(single: Boolean): MutableList<WheelAction> {
        val list = mutableListOf<WheelAction>()
        if (single) {
            list += view("Shows the document so you can read it first.")
            list += editor(
                "Edit", "change the text",
                "Opens the words in the text editor. Change anything, then save as Word, PDF or TXT. Word formatting like fonts, tables and pictures is not kept.",
                Editor.TEXT,
            )
        }
        list += perFile(
            "PDF", "simple PDF",
            "Makes a simple A4 PDF of the words. Fonts, tables and pictures from Word are not kept; only the text and paragraphs.",
            "Make PDF", "PDF ready",
        ) { c, f, _ -> listOf(DocOps.toPdf(c, f.baseName, DocOps.readText(c, f))) }
        list += perFile(
            "TXT", "plain text", "Saves only the words as a plain text file that opens anywhere.",
            "Make TXT", "Text saved",
        ) { c, f, _ -> listOf(DocOps.toTxt(c, f.baseName, DocOps.readText(c, f))) }
        list += perFile(
            "Word", "DOCX", "Makes a Word (.docx) file with the same words, one paragraph per line.",
            "Make Word file", "Word file ready",
        ) { c, f, _ -> listOf(DocOps.toDocx(c, f.baseName, DocOps.readText(c, f))) }
        return list
    }

    // ---------- Zip and any file ----------

    private fun zipConvert(single: Boolean): MutableList<WheelAction> {
        val list = mutableListOf<WheelAction>()
        if (single) list += view("Lists the files inside the zip without unpacking it.")
        list += perFile(
            "Unzip", "unpack", "Takes all files out of the zip into a folder.", "Unzip", "Unpacked",
        ) { c, f, _ -> ArchiveOps.unzip(c, f) }
        return list
    }

    private fun zip(count: Int) = WheelAction(
        "Zip", "pack together",
        if (count > 1) "Packs all $count files into one .zip file. Handy for sending many files at once."
        else "Packs the file into a .zip file. Handy for sending, and some files get smaller.",
        "Make zip", "Zipped",
    ) { c, fs, _ -> ActionResult(outputs = listOf(ArchiveOps.zip(c, fs))) }

    private fun hash() = WheelAction(
        "SHA-256", "file fingerprint",
        "Makes a fingerprint code of the file. If even one letter of the file changes, the code changes. People use it to check a download is the exact original. Copied to the clipboard.",
        "Make fingerprint", "Fingerprint copied",
    ) { c, fs, _ ->
        val text = fs.joinToString("\n") { f ->
            val h = ArchiveOps.sha256(c, f)
            if (fs.size == 1) h else "$h  ${f.name}"
        }
        ActionResult(clipboard = text, message = text)
    }
}
