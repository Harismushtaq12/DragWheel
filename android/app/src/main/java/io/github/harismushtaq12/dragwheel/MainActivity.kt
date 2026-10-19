package io.github.harismushtaq12.dragwheel

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.ui.DragWheelApp
import io.github.harismushtaq12.dragwheel.ui.DragWheelTheme
import io.github.harismushtaq12.dragwheel.ui.Outside

class MainActivity : ComponentActivity(), Outside {
    private val vm: WheelViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.setUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            DragWheelTheme {
                BackHandler(enabled = true) {
                    if (!vm.back()) finish()
                }
                DragWheelApp(vm, this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Files shared to us from another app's Share menu. */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> uris += streamListExtra(intent)
            else -> return
        }
        // Some apps only put the files in ClipData.
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
        }
        vm.setUris(uris.distinct())
    }

    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    // ---------- Outside ----------

    override fun chooseFiles() {
        picker.launch(arrayOf("*/*"))
    }

    override fun openElsewhere(uri: Uri, mime: String, name: String) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, "Open $name with"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app on this phone can open $name.", Toast.LENGTH_LONG).show()
        }
    }

    override fun share(files: List<SavedFile>) {
        if (files.isEmpty()) return
        val send = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, files[0].uri).setType(files[0].mime)
        } else {
            val mimes = files.map { it.mime }.distinct()
            val type = if (mimes.size == 1) mimes[0] else mimes.map { it.substringBefore('/') }.distinct()
                .singleOrNull()?.let { "$it/*" } ?: "*/*"
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.uri }))
                .setType(type)
        }
        val clip = ClipData.newRawUri(files[0].name, files[0].uri)
        files.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share"))
    }
}
