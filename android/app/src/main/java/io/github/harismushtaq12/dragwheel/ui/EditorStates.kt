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
