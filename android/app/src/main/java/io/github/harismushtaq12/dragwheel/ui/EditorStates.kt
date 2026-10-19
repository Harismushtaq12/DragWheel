package io.github.harismushtaq12.dragwheel.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.TextDoc
import io.github.harismushtaq12.dragwheel.ops.ImageOps
import io.github.harismushtaq12.dragwheel.ops.PageEdit

/*
 * Editor state lives with the screen entry in the back stack, not inside the composable.
 * So going Back from the preview returns to the editor with every change still there.
 */

class ImageEditState(file: InputFile) {
    var turns by mutableIntStateOf(0)
    var flip by mutableStateOf(false)
    var crop by mutableStateOf(Rect(0f, 0f, 1f, 1f))
    var brightness by mutableFloatStateOf(0f)
    var contrast by mutableFloatStateOf(1f)
    var saturation by mutableFloatStateOf(1f)
    var tab by mutableIntStateOf(0)
    var format by mutableStateOf(ImageOps.sameOrJpg(file))
}

class PagesEditState {
    val pages = mutableStateListOf<PageEdit>()
    /** Original page numbers (0-based) that are picked. */
    val selected = mutableStateListOf<Int>()
    var png by mutableStateOf(false)
}

class TextEditState(doc: TextDoc) {
    val tooLong = doc.text.length > EDIT_LIMIT
    var text by mutableStateOf(if (tooLong) doc.text.take(EDIT_LIMIT) else doc.text)
    var name by mutableStateOf(doc.baseName)
    var format by mutableStateOf("pdf")

    companion object {
        const val EDIT_LIMIT = 300_000
    }
}

/** Things added in the PDF editor, and which tool is in use. */
class PdfEditState {
    val marks = androidx.compose.runtime.mutableStateListOf<io.github.harismushtaq12.dragwheel.ops.Mark>()
    var tool by mutableStateOf(PdfTool.EDIT_TEXT)
    var color by mutableIntStateOf(android.graphics.Color.BLACK)
}

enum class PdfTool(val label: String, val help: String) {
    EDIT_TEXT("Change text", "Tap a line of text to change it."),
    ADD_TEXT("Add text", "Tap where the new text should start."),
    DRAW("Draw / sign", "Draw with your finger. Use it to sign."),
    HIGHLIGHT("Highlight", "Drag over text to mark it yellow."),
    WHITE_OUT("White-out", "Drag over anything to hide it under white."),
    MOVE("Scroll", "Scroll the pages. Nothing is changed."),
}

/** Word editor: the editable copy and the paragraph texts being edited. */
class WordEditState(val editor: io.github.harismushtaq12.dragwheel.ops.doc.DocxEditor) {
    val texts = androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(editor.texts) }
    var find by mutableStateOf("")
    var replace by mutableStateOf("")
    var asPdf by mutableStateOf(false)
}

/** Answers typed into a PDF form. */
class FormState(val fields: List<io.github.harismushtaq12.dragwheel.ops.FormField>) {
    val values = androidx.compose.runtime.mutableStateMapOf<String, String>().apply { fields.forEach { put(it.name, it.value) } }
    var flatten by mutableStateOf(false)
}
