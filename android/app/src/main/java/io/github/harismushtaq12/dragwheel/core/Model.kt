package io.github.harismushtaq12.dragwheel.core

import android.content.Context
import android.net.Uri

/** A file the user picked or shared with us. */
data class InputFile(
    val uri: Uri,
    val name: String,
    val mime: String,
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val baseName: String get() = if (name.contains('.')) name.substringBeforeLast('.') else name
}

/** A file we saved to Downloads/DragWheel. */
data class SavedFile(val uri: Uri, val name: String, val mime: String)

data class ActionResult(
    val outputs: List<SavedFile> = emptyList(),
    val clipboard: String? = null,
    val message: String? = null,
)

/** One item on the wheel. */
class WheelAction(
    val title: String,
    val hint: String,
    val done: String,
    val run: suspend (Context, List<InputFile>) -> ActionResult,
)
