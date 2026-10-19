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
import io.github.harismushtaq12.dragwheel.core.Options
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import io.github.harismushtaq12.dragwheel.core.TextDoc
import io.github.harismushtaq12.dragwheel.core.WheelAction
import io.github.harismushtaq12.dragwheel.ops.DocOps
import io.github.harismushtaq12.dragwheel.ops.MediaOps
import io.github.harismushtaq12.dragwheel.ops.PdfOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the user is. Kept as a stack so Back works everywhere. */
sealed interface Screen {
    data object Home : Screen
    data object Wheel : Screen
    data class View(val file: InputFile) : Screen
    data class ImageEdit(val file: InputFile) : Screen
    data class PdfPages(val file: InputFile) : Screen
    data class TextEdit(val doc: TextDoc) : Screen
    data class Result(val title: String, val result: ActionResult, val saved: List<SavedFile>? = null) : Screen
}

class WheelViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()

    var files by mutableStateOf<List<InputFile>>(emptyList())
        private set
    var info by mutableStateOf<FileInfo?>(null)
        private set
    var toolsMode by mutableStateOf(false)
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

    val actions: List<WheelAction> by derivedStateOf { Catalog.forFiles(files, toolsMode) }

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
            toolsMode = false
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
            Catalog.Kind.PDF -> FileInfo(size = f.size, pages = runCatching { PdfOps.pageCount(context, f) }.getOrNull())
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

    fun setMode(tools: Boolean) {
        if (working == null) toolsMode = tools
    }

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
        when (action.editor) {
            Editor.VIEW -> open(Screen.View(files[0]))
            Editor.IMAGE -> open(Screen.ImageEdit(files[0]))
            Editor.PDF_PAGES -> open(Screen.PdfPages(files[0]))
            Editor.TEXT -> openText(files[0])
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

    /** Runs work made by an editor screen (photo, pages, text). */
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
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user pressed back
            } catch (e: Throwable) {
                error = friendly(e)
            } finally {
                working = null
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
        setUris(listOf(output.uri))
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
