package io.github.harismushtaq12.dragwheel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.harismushtaq12.dragwheel.core.ActionResult
import io.github.harismushtaq12.dragwheel.core.Catalog
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.Storage
import io.github.harismushtaq12.dragwheel.core.WheelAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Status {
    data object Idle : Status
    data class Working(val title: String) : Status
    data class Done(val title: String, val result: ActionResult) : Status
    data class Failed(val title: String, val message: String) : Status
}

class WheelViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()

    var files by mutableStateOf<List<InputFile>>(emptyList())
        private set
    var toolsMode by mutableStateOf(false)
        private set
    var status by mutableStateOf<Status>(Status.Idle)
        private set

    val actions: List<WheelAction> by derivedStateOf { Catalog.forFiles(files, toolsMode) }

    fun setUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        files = uris.distinct().mapNotNull { runCatching { Storage.describe(context, it) }.getOrNull() }
        toolsMode = false
        status = Status.Idle
    }

    fun clear() {
        files = emptyList()
        toolsMode = false
        status = Status.Idle
    }

    fun setMode(tools: Boolean) {
        if (status is Status.Working) return
        toolsMode = tools
    }

    fun dismiss() {
        if (status !is Status.Working) status = Status.Idle
    }

    fun run(action: WheelAction) {
        if (status is Status.Working || files.isEmpty()) return
        val chosen = files
        status = Status.Working(action.title)
        viewModelScope.launch {
            status = try {
                val result = withContext(Dispatchers.IO) { action.run(context, chosen) }
                result.clipboard?.let { copy(it) }
                Status.Done(action.done, result)
            } catch (e: Throwable) {
                Status.Failed(action.title, friendly(e))
            }
        }
    }

    private fun copy(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("DragWheel", text))
    }

    private fun friendly(e: Throwable): String {
        val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return if (msg.length > 220) msg.take(220) + "…" else msg
    }
}
