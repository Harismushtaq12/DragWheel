package io.github.harismushtaq12.dragwheel.core

import android.content.Context
import io.github.harismushtaq12.dragwheel.ops.ArchiveOps
import io.github.harismushtaq12.dragwheel.ops.DocOps
import io.github.harismushtaq12.dragwheel.ops.ImageOps
import io.github.harismushtaq12.dragwheel.ops.MediaOps
import io.github.harismushtaq12.dragwheel.ops.OcrOps
import io.github.harismushtaq12.dragwheel.ops.PageEdit
import io.github.harismushtaq12.dragwheel.ops.PdfEditOps
import io.github.harismushtaq12.dragwheel.ops.PdfOps
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.ensureActive

/**
 * Decides what appears on the wheel for the chosen files, in three rings:
 * Convert (every format the file can become), Edit (view and change it), Tools (everything else).
 * Every action explains itself in plain words.
 */
object Catalog {
    private const val MAX_ITEMS = 12

    enum class Kind { IMAGE, AUDIO, VIDEO, PDF, WORD, TEXT, ZIP, OTHER }

    fun kindOf(f: InputFile): Kind = when {
        f.mime.startsWith("image/") -> Kind.IMAGE
        f.mime.startsWith("video/") -> Kind.VIDEO
        f.mime.startsWith("audio/") -> Kind.AUDIO
        f.mime == "application/pdf" || f.ext == "pdf" -> Kind.PDF
        f.mime == "application/zip" || f.ext == "zip" -> Kind.ZIP
        DocOps.isDocx(f) -> Kind.WORD
        DocOps.isText(f) -> Kind.TEXT
        f.ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif") -> Kind.IMAGE
        f.ext in setOf("mp4", "mov", "mkv", "webm", "3gp", "m4v") -> Kind.VIDEO
        f.ext in setOf("mp3", "m4a", "wav", "flac", "ogg", "opus", "aac", "amr") -> Kind.AUDIO
        else -> Kind.OTHER
    }

    fun kindOf(files: List<InputFile>): Kind {
        val kinds = files.map(::kindOf).distinct()
        return if (kinds.size == 1) kinds[0] else Kind.OTHER
    }

    fun forFiles(files: List<InputFile>, mode: Mode): List<WheelAction> {
        if (files.isEmpty()) return emptyList()
        val kind = kindOf(files)
        val one = files.size == 1
        val f = files[0]
        val list: MutableList<WheelAction> = when (mode) {
            Mode.CONVERT -> when (kind) {
                Kind.IMAGE -> imageConvert(f, files.size)
                Kind.PDF -> pdfConvert(files.size)
                Kind.WORD -> wordConvert()
                Kind.TEXT -> textConvert(f)
                Kind.VIDEO -> videoConvert()
                Kind.AUDIO -> audioConvert(f)
                Kind.ZIP -> mutableListOf(unzip())
                Kind.OTHER -> mutableListOf(zip(files.size))
            }
            Mode.EDIT -> when (kind) {
                Kind.IMAGE -> if (one) mutableListOf(view("Opens the picture full screen. Pinch to zoom."), photoEditor()) else mutableListOf()
                Kind.PDF -> if (one) pdfEdit() else mutableListOf()
                Kind.WORD -> if (one) wordEdit() else mutableListOf()
                Kind.TEXT -> if (one) mutableListOf(view("Shows the text so you can read it first."), textEditor()) else mutableListOf()
                Kind.VIDEO -> if (one) mutableListOf(view("Plays the video so you can check it."), trim(false)) else mutableListOf()
                Kind.AUDIO -> if (one) mutableListOf(view("Plays the sound so you can check it."), trim(true)) else mutableListOf()
                Kind.ZIP -> if (one) mutableListOf(view("Lists the files inside the zip without unpacking it.")) else mutableListOf()
                Kind.OTHER -> mutableListOf()
            }
            Mode.TOOLS -> when (kind) {
                Kind.IMAGE -> imageTools(one)
                Kind.PDF -> pdfTools()
                Kind.VIDEO -> videoTools()
                else -> mutableListOf()
            }
        }
        if (mode == Mode.TOOLS) {
            if (kind != Kind.ZIP) list += zip(files.size)
            list += hash()
        }
        return list.take(MAX_ITEMS)
    }

    // ---------- Shared pieces ----------

    private fun quality(default: Float = 85f) = OptionSpec.Slider(
        "quality", "Quality", 30f, 100f, 13, default,
    ) { v -> "${v.roundToInt()}%" + if (v >= 90) " (best)" else if (v < 60) " (smallest file)" else "" }

    private val pagesOption = OptionSpec.Text(
        "pages", "Pages", "All pages",
        help = "Leave empty for every page, or type pages like 1-3, 5",
    )

    private val imageType = OptionSpec.Choice("format", "Picture type", listOf("jpg" to "JPG (smaller)", "png" to "PNG (sharper)"), "jpg")

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

    private fun view(description: String) = editor("View", "look inside", description, Editor.VIEW)

    private fun textResult(title: String, hint: String, description: String, button: String, read: suspend (Context, InputFile) -> String) =
        WheelAction(title, hint, description, button, "") { c, fs, _ -> ActionResult(text = TextDoc(fs[0].baseName, read(c, fs[0]))) }

    // ---------- Images ----------

    private fun imageConvert(f: InputFile, count: Int): MutableList<WheelAction> {
        val own = when (f.ext) { "jpeg", "jfif" -> "jpg"; "heif" -> "heic"; else -> f.ext }
        val list = mutableListOf<WheelAction>()
        fun add(ext: String, a: WheelAction) { if (ext != own) list += a }
        add("jpg", perFile("JPG", "photo", "Makes a JPG copy. JPG works everywhere and keeps files small. Transparent areas become white.",
            "Convert to JPG", "Converted to JPG", listOf(quality(92f))) { c, x, o -> listOf(ImageOps.convert(c, x, "jpg", o.num("quality", 92f).roundToInt())) })
        add("png", perFile("PNG", "lossless", "Makes a PNG copy. PNG keeps full detail and transparency, but files are larger.",
            "Convert to PNG", "Converted to PNG") { c, x, _ -> listOf(ImageOps.convert(c, x, "png")) })
        add("webp", perFile("WebP", "for the web", "Makes a WebP copy. It looks like JPG but is usually about a third smaller. Good for websites.",
            "Convert to WebP", "Converted to WebP", listOf(quality(85f))) { c, x, o -> listOf(ImageOps.convert(c, x, "webp", o.num("quality", 85f).roundToInt())) })
        add("heic", perFile("HEIC", "iPhone format", "Makes a HEIC copy, the format iPhones use. About half the size of JPG. Some older phones cannot make HEIC.",
            "Convert to HEIC", "Converted to HEIC", listOf(quality(85f))) { c, x, o -> listOf(ImageOps.toHeic(c, x, o.num("quality", 85f).roundToInt())) })
        add("bmp", perFile("BMP", "plain bitmap", "Makes a BMP copy. Very large files, but every old program can open them.",
            "Convert to BMP", "Converted to BMP") { c, x, _ -> listOf(ImageOps.toBmp(c, x)) })
        add("ico", perFile("ICO", "icon", "Makes an icon (.ico) for Windows apps, folders or a website. The picture is fitted into a square.",
            "Make icon", "Icon ready",
            listOf(OptionSpec.Choice("size", "Size", listOf("256" to "256 px", "128" to "128 px", "64" to "64 px", "32" to "32 px"), "256"))) { c, x, o ->
            listOf(ImageOps.toIco(c, x, o.str("size", "256").toInt()))
        })
        list += WheelAction(
            "PDF", if (count > 1) "$count pages" else "one page",
            if (count > 1) "Puts all $count pictures into one PDF, one picture per page, in the order you chose them."
            else "Puts the picture on a PDF page.",
            "Make PDF", "PDF ready",
            listOf(OptionSpec.Choice("page", "Page size", listOf("a4" to "A4 paper", "fit" to "Same as picture"), "a4")),
        ) { c, fs, o -> ActionResult(outputs = listOf(ImageOps.toPdf(c, fs, a4 = o.str("page", "a4") == "a4"))) }
        list += WheelAction(
            "Word", "DOCX", if (count > 1) "Puts all $count pictures into one Word file." else "Puts the picture into a Word file.",
            "Make Word file", "Word file ready",
        ) { c, fs, _ -> ActionResult(outputs = listOf(ImageOps.toDocx(c, fs))) }
        if (count == 1) list += textResult(
            "Text", "read the words",
            "Reads the printed words in the picture (for example a photo of a page or a sign) and opens them in the text editor. Works best with clear, straight photos. Latin letters only.",
            "Read text",
        ) { c, x -> OcrOps.readImage(c, x) }
        return list
    }

    private fun photoEditor() = editor(
        "Edit photo", "crop, turn, colour",
        "Opens the photo editor: crop, turn, flip, and change brightness, contrast and colour. You see the result before anything is saved.",
        Editor.IMAGE,
    )

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
            perFile("Rotate", "90° right", "Turns the picture a quarter turn to the right.", "Rotate", "Rotated") { c, f, _ -> listOf(ImageOps.rotate(c, f)) },
            perFile("Grayscale", "black & white", "Removes all colour and keeps only shades of grey.", "Make black & white", "Done") { c, f, _ -> listOf(ImageOps.grayscale(c, f)) },
            perFile(
                "Clean", "no hidden details",
                "Removes hidden details stored in the photo, like GPS location, date and camera model. Good before posting online.",
                "Remove details", "Hidden details removed",
            ) { c, f, _ -> listOf(ImageOps.clean(c, f)) },
        )
        if (single) list += WheelAction(
            "Read QR", "copy text", "Looks for a QR code or barcode in the picture and copies what it says.", "Read code", "Code found",
        ) { c, fs, _ ->
            val text = ImageOps.readQr(c, fs[0])
            ActionResult(clipboard = text, message = text)
        }
        return list
    }

    // ---------- PDF ----------

    private fun pdfConvert(count: Int): MutableList<WheelAction> {
        val list = mutableListOf<WheelAction>()
        if (count > 1) list += WheelAction(
            "Merge", "$count into one", "Joins all $count PDFs into one PDF, in the order you chose them.", "Merge PDFs", "Merged",
        ) { c, fs, _ -> ActionResult(outputs = listOf(PdfOps.merge(c, fs))) }
        list += perFile(
            "Word", "editable",
            "Makes a Word file you can edit: the words, paragraphs, bold text and bigger titles. Exact page layout, tables and pictures are not kept. For an exact copy use \"Word (exact)\".",
            "Convert to Word", "Word file ready",
        ) { c, f, _ -> listOf(PdfEditOps.toWordEditable(c, f)) }
        list += perFile(
            "Word (exact)", "looks the same",
            "Makes a Word file where every page looks exactly like the PDF. Each page is a picture, so the words cannot be edited.",
            "Convert to Word", "Word file ready",
        ) { c, f, _ -> listOf(PdfEditOps.toWordExact(c, f)) }
        list += perFile(
            "JPG", "pages", "Turns pages into JPG pictures, one per page. Choose every page or only some.", "Make pictures", "Pictures ready",
            listOf(OptionSpec.Choice("dpi", "Sharpness", listOf("100" to "Screen", "150" to "Normal", "300" to "Print"), "150"), pagesOption),
        ) { c, f, o -> PdfOps.toImages(c, f, png = false, dpi = o.str("dpi", "150").toInt()) { n -> parsePages(o.str("pages"), n).map { PageEdit(it) } } }
        list += perFile(
            "PNG", "sharp pages", "Turns pages into PNG pictures, one per page. Sharper than JPG, larger files.", "Make pictures", "Pictures ready",
            listOf(OptionSpec.Choice("dpi", "Sharpness", listOf("100" to "Screen", "150" to "Normal", "300" to "Print"), "150"), pagesOption),
        ) { c, f, o -> PdfOps.toImages(c, f, png = true, dpi = o.str("dpi", "150").toInt()) { n -> parsePages(o.str("pages"), n).map { PageEdit(it) } } }
        list += perFile(
            "Long image", "one tall picture", "Puts all pages one under the other in one tall picture. Easy to send in chat apps.",
            "Make long image", "Long image ready", listOf(imageType),
        ) { c, f, o -> listOf(PdfEditOps.longImage(c, f, png = o.str("format", "jpg") == "png")) }
        list += textResult(
            "Text", "copy the words",
            "Takes the words out of the PDF and opens them in the text editor, where you can save them as Word, PDF or TXT.",
            "Get text",
        ) { c, f -> PdfOps.extractText(c, f) }
        list += textResult(
            "Scan to text", "read scanned pages",
            "For scanned PDFs (pages that are photos): reads the printed words from each page. Takes a little longer. Latin letters only.",
            "Read text",
        ) { c, f -> OcrOps.readPdf(c, f) }
        list += perFile(
            "Pictures", "images inside", "Saves the photos and pictures that are inside the PDF, at their real size.",
            "Save pictures", "Pictures saved",
        ) { c, f, _ -> PdfEditOps.extractImages(c, f) }
        return list
    }

    private fun pdfEdit(): MutableList<WheelAction> = mutableListOf(
        view("Shows every page so you can read the PDF first."),
        editor(
            "Edit PDF", "text, sign, mark",
            "Change the PDF itself: tap a line of text to change it, add new text, draw or sign, highlight, or hide parts with white-out. Everything else in the PDF stays exactly as it was.",
            Editor.PDF_EDIT,
        ),
        editor(
            "Pages", "move, turn, delete",
            "Shows all pages. Pick pages to turn, delete or move, then save a new PDF, or turn the pages you pick into pictures.",
            Editor.PDF_PAGES,
        ),
        editor(
            "Fill form", "type in fields",
            "If the PDF is a form (with boxes to fill in), type your answers into each field and tick the boxes.",
            Editor.PDF_FORM,
        ),
    )

    private fun pdfTools(): MutableList<WheelAction> = mutableListOf(
        perFile(
            "Compress", "smaller PDF",
            "Makes the PDF smaller by saving the photos inside it at a lower size. Text stays sharp. Best for scanned PDFs and PDFs with many photos.",
            "Compress PDF", "Compressed",
            listOf(quality(60f), OptionSpec.Choice("max", "Largest picture side", listOf("2000" to "2000 px", "1400" to "1400 px", "1000" to "1000 px"), "1400")),
        ) { c, f, o -> listOf(PdfEditOps.compress(c, f, o.num("quality", 60f).roundToInt(), o.str("max", "1400").toInt())) },
        perFile(
            "Numbers", "page numbers", "Adds a page number at the bottom of every page.", "Add numbers", "Numbers added",
            listOf(
                OptionSpec.Choice("style", "Style", listOf("n" to "1", "of" to "1 of 9", "page" to "Page 1"), "n"),
                OptionSpec.Choice("pos", "Place", listOf("left" to "Left", "center" to "Middle", "right" to "Right"), "center"),
            ),
        ) { c, f, o -> listOf(PdfEditOps.pageNumbers(c, f, o.str("style", "n"), o.str("pos", "center"))) },
        perFile(
            "Watermark", "faint text", "Writes faint, slanted text across every page, like CONFIDENTIAL or your name.", "Add watermark", "Watermark added",
            listOf(OptionSpec.Text("text", "Watermark text", "CONFIDENTIAL", required = true)),
        ) { c, f, o -> listOf(PdfEditOps.watermark(c, f, o.str("text"))) },
        perFile(
            "Split", "page by page", "Saves pages as separate PDF files, one file per page. Choose every page or only some.",
            "Split PDF", "Split", listOf(pagesOption),
        ) { c, f, o -> PdfOps.split(c, f) { n -> parsePages(o.str("pages"), n) } },
        perFile(
            "Rotate", "all pages", "Turns every page a quarter turn to the right. To turn only some pages, use Edit, then Pages.",
            "Rotate pages", "Rotated",
        ) { c, f, _ -> listOf(PdfOps.rotateAll(c, f)) },
        perFile("Reverse", "page order", "Puts the pages in the opposite order, last page first.", "Reverse order", "Reversed") { c, f, _ -> listOf(PdfOps.reverse(c, f)) },
        perFile(
            "Lock", "add password",
            "Adds a password. Anyone opening the PDF must type it. Do not forget it: it cannot be recovered.",
            "Lock PDF", "Locked",
            listOf(OptionSpec.Text("password", "New password", "At least 4 characters", password = true, required = true)),
        ) { c, f, o -> listOf(PdfOps.lock(c, f, o.str("password"))) },
        perFile(
            "Unlock", "remove password", "Removes the password from a PDF you are allowed to open. You need the current password.",
            "Unlock PDF", "Unlocked",
            listOf(OptionSpec.Text("password", "Current password", "Password", password = true, required = true)),
        ) { c, f, o -> listOf(PdfOps.unlock(c, f, o.str("password"))) },
    )

    // ---------- Word and text ----------

    private fun wordConvert(): MutableList<WheelAction> = mutableListOf(
        perFile(
            "PDF", "keeps the look",
            "Makes a PDF that keeps the document's look: headings, bold, italic, sizes, colours, tables and pictures. Very complex layouts (columns, text boxes) may look simpler.",
            "Convert to PDF", "PDF ready",
        ) { c, f, _ -> listOf(DocOps.toPdf(c, f.baseName, DocOps.model(c, f))) },
        perFile("JPG", "pages", "Turns each page into a JPG picture.", "Make pictures", "Pictures ready") { c, f, _ ->
            DocOps.toImages(c, f.baseName, DocOps.model(c, f), png = false)
        },
        perFile("PNG", "sharp pages", "Turns each page into a PNG picture.", "Make pictures", "Pictures ready") { c, f, _ ->
            DocOps.toImages(c, f.baseName, DocOps.model(c, f), png = true)
        },
        perFile("HTML", "web page", "Makes a web page with the same headings, bold text, colours, tables and pictures.", "Make web page", "Web page ready") { c, f, _ ->
            listOf(DocOps.toHtml(c, f.baseName, DocOps.model(c, f)))
        },
        perFile("TXT", "plain text", "Saves only the words as a plain text file that opens anywhere.", "Make TXT", "Text saved") { c, f, _ ->
            listOf(DocOps.toTxt(c, f.baseName, DocOps.readText(c, f)))
        },
    )

    private fun wordEdit(): MutableList<WheelAction> = mutableListOf(
        view("Shows the document with its formatting, page by page."),
        editor(
            "Edit Word", "keeps formatting",
            "Change the words of any paragraph, or find and replace. Fonts, bold, colours, tables, pictures and page layout all stay exactly as they were. Save as Word or PDF.",
            Editor.WORD,
        ),
        editor(
            "Plain edit", "text only",
            "Edits the words only, without any formatting, and saves as a new PDF, Word or TXT file.",
            Editor.TEXT,
        ),
    )

    private fun textConvert(f: InputFile): MutableList<WheelAction> {
        val list = mutableListOf(
            perFile("PDF", "A4 pages", "Makes a PDF of the text on A4 pages.", "Make PDF", "PDF ready") { c, x, _ ->
                listOf(DocOps.toPdf(c, x.baseName, DocOps.readText(c, x)))
            },
            perFile("Word", "DOCX", "Makes a Word file with the same text, one paragraph per line.", "Make Word file", "Word file ready") { c, x, _ ->
                listOf(DocOps.toDocx(c, x.baseName, DocOps.readText(c, x)))
            },
            perFile("HTML", "web page", "Makes a simple web page of the text.", "Make web page", "Web page ready") { c, x, _ ->
                listOf(DocOps.toHtml(c, x.baseName, DocOps.model(c, x)))
            },
            perFile("JPG", "pages", "Turns the text pages into JPG pictures.", "Make pictures", "Pictures ready") { c, x, _ ->
                DocOps.toImages(c, x.baseName, DocOps.model(c, x), png = false)
            },
        )
        if (f.ext != "txt") list += perFile("TXT", "plain text", "Saves the same text as a .txt file.", "Make TXT", "Text saved") { c, x, _ ->
            listOf(DocOps.toTxt(c, x.baseName, DocOps.readText(c, x)))
        }
        return list
    }

    private fun textEditor() = editor(
        "Edit text", "change words",
        "Opens the words in the text editor. Change anything, then save as PDF, Word or TXT.",
        Editor.TEXT,
    )

    // ---------- Video and audio ----------

    private fun videoConvert(): MutableList<WheelAction> = mutableListOf(
        perFile("MP4", "plays anywhere", "Makes an MP4 (H.264) copy. MP4 plays on almost every phone, computer and website.",
            "Convert to MP4", "Converted to MP4") { c, f, _ -> listOf(MediaOps.toMp4(c, f)) },
        perFile("M4A", "sound only", "Saves only the sound as an M4A file. Good for songs, lectures and voice notes.",
            "Save sound", "Sound saved") { c, f, _ -> listOf(MediaOps.audioOnly(c, f)) },
        perFile("WAV", "sound, full quality", "Saves only the sound as an uncompressed WAV file. Large, but every audio program can open it.",
            "Save as WAV", "WAV saved") { c, f, _ -> listOf(MediaOps.toWav(c, f)) },
        perFile("Pictures", "every few seconds", "Saves a picture from the video every few seconds (up to 120 pictures).",
            "Save pictures", "Pictures saved",
            listOf(OptionSpec.Choice("every", "One picture every", listOf("1" to "1 s", "2" to "2 s", "5" to "5 s", "10" to "10 s"), "5"))) { c, f, o ->
            MediaOps.frames(c, f, o.str("every", "5").toInt())
        },
        perFile("First picture", "cover frame", "Saves the first frame of the video as a PNG picture.",
            "Save picture", "Picture saved") { c, f, _ -> listOf(MediaOps.firstFrame(c, f)) },
    )

    private fun videoTools(): MutableList<WheelAction> = mutableListOf(
        perFile(
            "Compress", "smaller video",
            "Makes the video file smaller for sending. It is saved again at a lower bitrate, and big videos are scaled down. Small videos are never made bigger.",
            "Compress video", "Compressed",
            listOf(OptionSpec.Choice("height", "Size", listOf("1080" to "1080p (sharp)", "720" to "720p (balanced)", "480" to "480p (smallest)"), "720")),
        ) { c, f, o -> listOf(MediaOps.compress(c, f, o.str("height", "720").toInt())) },
        perFile("Mute", "remove sound", "Makes a copy of the video with no sound.", "Remove sound", "Muted") { c, f, _ -> listOf(MediaOps.mute(c, f)) },
        perFile("Rotate", "90° right", "Turns the video a quarter turn to the right. Useful for sideways phone videos.",
            "Rotate video", "Rotated") { c, f, _ -> listOf(MediaOps.rotate(c, f)) },
    )

    private fun audioConvert(f: InputFile): MutableList<WheelAction> {
        val list = mutableListOf<WheelAction>()
        if (f.ext != "m4a") list += perFile("M4A", "AAC audio", "Makes an M4A (AAC) copy. It sounds like MP3 and plays on all phones and computers.",
            "Convert to M4A", "Converted to M4A") { c, x, _ -> listOf(MediaOps.audioOnly(c, x)) }
        if (f.ext != "wav") list += perFile("WAV", "full quality", "Makes an uncompressed WAV copy. Large, but every audio program can open it.",
            "Convert to WAV", "Converted to WAV") { c, x, _ -> listOf(MediaOps.toWav(c, x)) }
        return list
    }

    private fun trim(isAudio: Boolean) = perFile(
        "Trim", "cut start/end",
        if (isAudio) "Keeps only the part between the start and end you choose. Saved as M4A."
        else "Keeps only the part between the start and end you choose. The rest is cut away.",
        if (isAudio) "Trim sound" else "Trim video", "Trimmed", listOf(OptionSpec.Range("range", "Keep")),
    ) { c, f, o -> listOf(MediaOps.trim(c, f, o.range("range") ?: throw IllegalArgumentException("Choose start and end"), isAudio)) }

    // ---------- Zip and any file ----------

    private fun unzip() = perFile("Unzip", "unpack", "Takes all files out of the zip into a folder.", "Unzip", "Unpacked") { c, f, _ -> ArchiveOps.unzip(c, f) }

    private fun zip(count: Int) = WheelAction(
        "Zip", "pack together",
        if (count > 1) "Packs all $count files into one .zip file. Handy for sending many files at once."
        else "Packs the file into a .zip file. Handy for sending, and some files get smaller.",
        "Make zip", "Zipped",
    ) { c, fs, _ -> ActionResult(outputs = listOf(ArchiveOps.zip(c, fs))) }

    private fun hash() = WheelAction(
        "Fingerprint", "SHA-256",
        "Makes a fingerprint code (SHA-256) of the file. If even one letter of the file changes, the code changes. People use it to check a download is the exact original. Copied to the clipboard.",
        "Make fingerprint", "Fingerprint copied",
    ) { c, fs, _ ->
        val text = fs.joinToString("\n") { f ->
            val h = ArchiveOps.sha256(c, f)
            if (fs.size == 1) h else "$h  ${f.name}"
        }
        ActionResult(clipboard = text, message = text)
    }
}
