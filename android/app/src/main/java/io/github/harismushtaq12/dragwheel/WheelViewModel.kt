package io.github.harismushtaq12.dragwheel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.harismushtaq12.dragwheel.core.ActionResult
import io.github.harismushtaq12.dragwheel.core.Catalog
import io.github.harismushtaq12.dragwheel.core.Editor
import io.github.harismushtaq12.dragwheel.core.FileInfo
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.Mode
import io.github.harismushtaq12.dragwheel.core.Options
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import io.github.harismushtaq12.dragwheel.core.TextDoc
import io.github.harismushtaq12.dragwheel.core.WheelAction
import io.github.harismushtaq12.dragwheel.ops.DocOps
import io.github.harismushtaq12.dragwheel.ops.FormField
import io.github.harismushtaq12.dragwheel.ops.MediaOps
import io.github.harismushtaq12.dragwheel.ops.PdfEditOps
import io.github.harismushtaq12.dragwheel.ops.PdfOps
import io.github.harismushtaq12.dragwheel.ops.doc.DocxEditor
import io.github.harismushtaq12.dragwheel.ui.FormState
import io.github.harismushtaq12.dragwheel.ui.ImageEditState
import io.github.harismushtaq12.dragwheel.ui.PagesEditState
import io.github.harismushtaq12.dragwheel.ui.PdfEditState
import io.github.harismushtaq12.dragwheel.ui.TextEditState
import io.github.harismushtaq12.dragwheel.ui.WordEditState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the user is. Kept as a stack so Back works everywhere. Editors keep their state in their entry. */
sealed interface Screen {
    data object Home : Screen
    data object Wheel : Screen
    data class View(val file: InputFile) : Screen
    data class ImageEdit(val file: InputFile) : Screen {
        val state = ImageEditState(file)
    }
    data class PdfPages(val file: InputFile) : Screen {
        val state = PagesEditState()
    }
    data class PdfEdit(val file: InputFile) : Screen {
        val state = PdfEditState()
    }
    class PdfForm(val file: InputFile, fields: List<FormField>) : Screen {
        val state = FormState(fields)
    }
    class WordEdit(val file: InputFile, editor: DocxEditor) : Screen {
        val state = WordEditState(editor)
    }
    data class TextEdit(val doc: TextDoc) : Screen {
        val state = TextEditState(doc)
    }
    data class Result(val title: String, val result: ActionResult, val saved: List<SavedFile>? = null) : Screen
}

class WheelViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()

    var files by mutableStateOf<List<InputFile>>(emptyList())
        private set
    var info by mutableStateOf<FileInfo?>(null)
        private set
    var mode by mutableStateOf(Mode.CONVERT)
        private set

    /** Action whose explanation card is open. */
    var pending by mutableStateOf<WheelAction?>(null)
        private set

    /** Text of the job in progress, or null. */
    var working by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    private val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()

    val actions: List<WheelAction> by derivedStateOf { Catalog.forFiles(files, mode) }

    private var job: Job? = null

    // ---------- Files ----------

    fun setUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val described = withContext(Dispatchers.IO) {
                uris.distinct().mapNotNull { runCatching { Storage.describe(context, it) }.getOrNull() }
            }
            if (described.isEmpty()) {
                error = "Could not open the file."
                return@launch
            }
            files = described
            mode = Mode.CONVERT
            pending = null
            info = null
            stack.clear()
            stack += Screen.Home
            stack += Screen.Wheel
            info = withContext(Dispatchers.IO) { loadInfo(described[0]) }
        }
    }

    private fun loadInfo(f: InputFile): FileInfo = runCatching {
        when (Catalog.kindOf(f)) {
            Catalog.Kind.VIDEO, Catalog.Kind.AUDIO -> MediaOps.info(context, f)
            Catalog.Kind.PDF -> FileInfo(
                size = f.size,
                pages = runCatching { PdfOps.openRenderer(Storage.localCopy(context, f)).use { it.pageCount } }.getOrNull(),
            )
            else -> FileInfo(size = f.size)
        }
    }.getOrElse { FileInfo(size = f.size) }

    fun clear() {
        files = emptyList()
        info = null
        pending = null
        stack.clear()
        stack += Screen.Home
    }

    fun setMode(m: Mode) {
        if (working == null) mode = m
    }

    fun nextMode() = setMode(Mode.entries[(mode.ordinal + 1) % Mode.entries.size])

    // ---------- Navigation ----------

    fun open(screen: Screen) {
        stack += screen
    }

    /** Returns false when there is nowhere to go back to. */
    fun back(): Boolean {
        if (pending != null) {
            pending = null
            return true
        }
        if (working != null) {
            job?.cancel()
            job = null
            working = null
            return true
        }
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    // ---------- Actions ----------

    fun pick(action: WheelAction) {
        if (working != null) return
        val f = files.firstOrNull() ?: return
        when (action.editor) {
            Editor.VIEW -> open(Screen.View(f))
            Editor.IMAGE -> open(Screen.ImageEdit(f))
            Editor.PDF_PAGES -> open(Screen.PdfPages(f))
            Editor.PDF_EDIT -> open(Screen.PdfEdit(f))
            Editor.PDF_FORM -> runJob("Reading the form") {
                val fields = PdfEditOps.formFields(context, f)
                if (fields.isEmpty()) throw IllegalStateException("This PDF has no fields to fill in. To type on it anyway, use Edit PDF.")
                withContext(Dispatchers.Main) { open(Screen.PdfForm(f, fields)) }
            }
            Editor.WORD -> runJob("Opening ${f.name}") {
                val editor = DocOps.openEditor(context, f)
                withContext(Dispatchers.Main) { open(Screen.WordEdit(f, editor)) }
            }
            Editor.TEXT -> openText(f)
            null -> pending = action
        }
    }

    fun dismissSheet() {
        pending = null
    }

    fun openText(file: InputFile) {
        runJob("Opening ${file.name}") {
            val text = DocOps.readText(context, file)
            withContext(Dispatchers.Main) { open(Screen.TextEdit(TextDoc(file.baseName, text))) }
        }
    }

    fun run(action: WheelAction, options: Options) {
        val chosen = files
        pending = null
        runJob(action.button) {
            Storage.beginJob(context)
            val result = action.run(context, chosen, options)
            withContext(Dispatchers.Main) { show(action.done, result) }
        }
    }

    /** Runs work made by an editor screen (photo, pages, text, PDF, Word, form). */
    fun runEditor(label: String, done: String, work: suspend (Context) -> ActionResult) {
        runJob(label) {
            Storage.beginJob(context)
            val result = work(context)
            withContext(Dispatchers.Main) { show(done, result) }
        }
    }

    private fun show(done: String, result: ActionResult) {
        result.clipboard?.let(::copy)
        if (result.text != null) open(Screen.TextEdit(result.text))
        else open(Screen.Result(done, result))
    }

    private fun runJob(label: String, block: suspend () -> Unit) {
        if (working != null) return
        working = label
        error = null
        job = viewModelScope.launch {
            val me = coroutineContext[Job]
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user pressed Cancel or Back
            } catch (e: Throwable) {
                if (job === me) error = friendly(e)
            } finally {
                // A cancelled job that finishes late must not hide a newer job's progress.
                if (job === me) {
                    working = null
                    job = null
                }
            }
        }
    }

    /** Copies results to Downloads/DragWheel. */
    fun saveResult(r: Screen.Result) {
        if (r.saved != null || working != null) return
        viewModelScope.launch {
            try {
                working = "Saving"
                val saved = withContext(Dispatchers.IO) { Storage.publish(context, r.result.outputs) }
                val i = stack.indexOf(r)
                if (i >= 0) stack[i] = r.copy(saved = saved)
                toast = if (saved.size == 1) "Saved to Downloads/DragWheel" else "Saved ${saved.size} files to Downloads/DragWheel"
            } catch (e: Throwable) {
                error = friendly(e)
            } finally {
                working = null
            }
        }
    }

    /** Use a result as the new input, to keep working on it. */
    fun continueWith(output: SavedFile) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { Storage.keepAsInput(context, output) }.getOrNull() }
            if (uri != null) setUris(listOf(uri)) else error = "Could not open the result."
        }
    }

    fun dismissError() {
        error = null
    }

    fun copy(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("DragWheel", text))
    }

    private fun friendly(e: Throwable): String {
        val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return when {
            e is OutOfMemoryError -> "This file is too big for the phone's memory. Try a smaller size."
            msg.length > 240 -> msg.take(240) + "…"
            else -> msg
        }
    }
}
