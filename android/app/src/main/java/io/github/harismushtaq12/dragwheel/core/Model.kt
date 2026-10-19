package io.github.harismushtaq12.dragwheel.core

import android.content.Context
import android.net.Uri
import java.io.File

/** A file the user picked or shared with us. */
data class InputFile(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long = -1,
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val baseName: String get() = if (name.contains('.')) name.substringBeforeLast('.') else name
}

/** A result file. It lives in the app's private workspace until the user saves it. */
data class SavedFile(
    val uri: Uri,
    val name: String,
    val mime: String,
    val file: File? = null,
    val subfolder: String? = null,
) {
    fun asInput() = InputFile(uri, name, mime, file?.length() ?: -1)
}

/** Text opened in the text editor. */
data class TextDoc(val baseName: String, val text: String)

data class ActionResult(
    val outputs: List<SavedFile> = emptyList(),
    val clipboard: String? = null,
    val message: String? = null,
    /** Open this text in the editor instead of showing a result screen. */
    val text: TextDoc? = null,
)

/** Facts about a file, shown to the user and used for options (e.g. video length). */
data class FileInfo(
    val size: Long = -1,
    val pages: Int? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class TimeRange(val startMs: Long, val endMs: Long)

/** Something the user can set before an action runs. */
sealed class OptionSpec(val key: String, val label: String) {
    /** Pick one of a few values. [choices] are (value, label). */
    class Choice(key: String, label: String, val choices: List<Pair<String, String>>, val default: String) :
        OptionSpec(key, label)

    class Slider(
        key: String, label: String,
        val min: Float, val max: Float, val steps: Int, val default: Float,
        val format: (Float) -> String,
    ) : OptionSpec(key, label)

    /** Start and end time, for video and audio. */
    class Range(key: String, label: String) : OptionSpec(key, label)

    class Text(
        key: String, label: String,
        val placeholder: String,
        val password: Boolean = false,
        val required: Boolean = false,
        val help: String? = null,
    ) : OptionSpec(key, label)
}

typealias Options = Map<String, Any>

fun Options.str(key: String, default: String = ""): String = (this[key] as? String) ?: default
fun Options.num(key: String, default: Float): Float = (this[key] as? Float) ?: default
fun Options.range(key: String): TimeRange? = this[key] as? TimeRange

/** Screens that an action can open instead of running straight away. */
enum class Editor { VIEW, IMAGE, PDF_PAGES, PDF_EDIT, PDF_FORM, WORD, TEXT }

/** The three rings of the wheel. */
enum class Mode(val label: String) { CONVERT("Convert"), EDIT("Edit"), TOOLS("Tools") }

/** One item on the wheel. */
class WheelAction(
    /** Short label on the wheel. */
    val title: String,
    /** Two or three words under the title. */
    val hint: String,
    /** Plain explanation shown before running. */
    val description: String,
    /** Label of the run button, e.g. "Convert to JPG". */
    val button: String,
    /** Title of the result screen, e.g. "Converted". */
    val done: String,
    val options: List<OptionSpec> = emptyList(),
    val editor: Editor? = null,
    val run: suspend (Context, List<InputFile>, Options) -> ActionResult = { _, _, _ -> ActionResult() },
)

/** "1-3, 5" -> [0, 1, 2, 4]. Empty means all pages. */
fun parsePages(text: String, count: Int): List<Int> {
    if (text.isBlank()) return (0 until count).toList()
    val out = sortedSetOf<Int>()
    for (part in text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
        val bits = part.split('-').map { it.trim() }
        val nums = bits.map { it.toIntOrNull() ?: throw IllegalArgumentException("Pages must look like 1-3, 5") }
        val (a, b) = when (nums.size) {
            1 -> nums[0] to nums[0]
            2 -> minOf(nums[0], nums[1]) to maxOf(nums[0], nums[1])
            else -> throw IllegalArgumentException("Pages must look like 1-3, 5")
        }
        for (p in a..b) if (p in 1..count) out += p - 1
    }
    if (out.isEmpty()) throw IllegalArgumentException("None of those pages exist. This PDF has $count pages.")
    return out.toList()
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
