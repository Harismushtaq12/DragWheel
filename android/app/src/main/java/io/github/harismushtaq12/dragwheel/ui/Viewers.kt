package io.github.harismushtaq12.dragwheel.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.harismushtaq12.dragwheel.core.Catalog
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.Storage
import io.github.harismushtaq12.dragwheel.core.formatSize
import io.github.harismushtaq12.dragwheel.ops.ArchiveOps
import io.github.harismushtaq12.dragwheel.ops.DocOps
import io.github.harismushtaq12.dragwheel.ops.ImageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shows any file inside the app. */
@Composable
fun FileViewer(file: InputFile, modifier: Modifier = Modifier) {
    when (Catalog.kindOf(file)) {
        Catalog.Kind.IMAGE -> ZoomImage(file, modifier)
        Catalog.Kind.PDF -> PdfView(file, modifier)
        Catalog.Kind.VIDEO -> MediaView(file, isAudio = false, modifier)
        Catalog.Kind.AUDIO -> MediaView(file, isAudio = true, modifier)
        Catalog.Kind.DOC -> TextPreview(file, modifier)
        Catalog.Kind.ZIP -> ZipList(file, modifier)
        Catalog.Kind.OTHER -> Message("No preview for this kind of file.\n${file.name}", modifier)
    }
}

@Composable
fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.Muted, fontSize = 15.sp)
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.Mint)
    }
}

sealed interface Load<out T> {
    data object Busy : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

// ---------- Pictures ----------

@Composable
private fun ZoomImage(file: InputFile, modifier: Modifier) {
    val context = LocalContext.current
    val state by produceState<Load<Bitmap>>(Load.Busy, file.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { Load.Ready(ImageOps.load(context, file, maxSide = 2560)) }
                .getOrElse { Load.Failed("Cannot show this picture.") }
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    when (val s = state) {
        is Load.Busy -> Loading(modifier)
        is Load.Failed -> Message(s.message, modifier)
        is Load.Ready -> Box(
            modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = s.value.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

// ---------- PDF ----------

/** Wraps Android's PdfRenderer, which may only render one page at a time. */
class PdfSource(file: java.io.File) {
    private val renderer: PdfRenderer = io.github.harismushtaq12.dragwheel.ops.PdfOps.openRenderer(file)
    private val lock = Mutex()
    val pageCount: Int = renderer.pageCount
    val sizes: List<Pair<Int, Int>> = (0 until pageCount).map { i ->
        renderer.openPage(i).use { it.width to it.height }
    }

    suspend fun render(index: Int, widthPx: Int, quarterTurns: Int = 0): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val w = widthPx.coerceIn(64, 2400)
                val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                if (quarterTurns % 4 == 0) bitmap else ImageOps.transform(bitmap, quarterTurns, false)
            }
        }
    }

    /** Waits for any page being drawn, then closes. */
    suspend fun close() {
        lock.withLock { withContext(Dispatchers.IO) { runCatching { renderer.close() } } }
    }
}

@Composable
fun rememberPdfSource(file: InputFile): Load<PdfSource> {
    val context = LocalContext.current
    val state = produceState<Load<PdfSource>>(Load.Busy, file.uri) {
        val made = withContext(Dispatchers.IO) {
            try {
                Load.Ready(PdfSource(Storage.localCopy(context, file)))
            } catch (e: SecurityException) {
                Load.Failed("This PDF is locked with a password. Use Tools, then Unlock.")
            } catch (e: Exception) {
                Load.Failed("Cannot open this PDF.")
            }
        }
        value = made
        awaitDispose {
            (made as? Load.Ready)?.value?.let { source -> CoroutineScope(Dispatchers.IO).launch { source.close() } }
        }
    }
    return state.value
}

@Composable
private fun PdfView(file: InputFile, modifier: Modifier) {
    when (val s = rememberPdfSource(file)) {
        is Load.Busy -> Loading(modifier)
        is Load.Failed -> Message(s.message, modifier)
        is Load.Ready -> BoxWithConstraints(modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(s.value.pageCount) { i ->
                    PdfPageImage(s.value, i, widthPx, Modifier.fillMaxWidth())
                    Text("Page ${i + 1} of ${s.value.pageCount}", color = Palette.Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
fun PdfPageImage(source: PdfSource, index: Int, widthPx: Int, modifier: Modifier = Modifier, quarterTurns: Int = 0) {
    val (w, h) = source.sizes[index]
    val ratio = if (quarterTurns % 2 == 0) w.toFloat() / h else h.toFloat() / w
    val bitmap by produceState<Bitmap?>(null, source, index, widthPx, quarterTurns) {
        value = runCatching { source.render(index, widthPx, quarterTurns) }.getOrNull()
    }
    Box(modifier.aspectRatio(ratio.coerceIn(0.2f, 5f)).background(Color.White)) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize())
        }
    }
}

// ---------- Video and audio ----------

@Composable
private fun MediaView(file: InputFile, isAudio: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        if (isAudio) {
            Text("♪", color = Palette.Mint, fontSize = 64.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(file.name, color = Palette.Text, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            Text("Use the controls below to play.", color = Palette.Muted, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val controller = MediaController(ctx)
                    tag = controller
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setVideoURI(file.uri)
                    setOnPreparedListener { player ->
                        player.isLooping = false
                        controller.show(0)
                    }
                    requestFocus()
                }
            },
            onRelease = { view ->
                (view.tag as? MediaController)?.hide()
                view.stopPlayback()
            },
            modifier = if (isAudio) Modifier.fillMaxWidth().height(72.dp) else Modifier.fillMaxWidth().weight(1f),
        )
    }
}

// ---------- Documents ----------

@Composable
private fun TextPreview(file: InputFile, modifier: Modifier) {
    val context = LocalContext.current
    val state by produceState<Load<String>>(Load.Busy, file.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { Load.Ready(DocOps.readText(context, file).take(300_000)) }
                .getOrElse { Load.Failed(it.message ?: "Cannot read this document.") }
        }
    }
    when (val s = state) {
        is Load.Busy -> Loading(modifier)
        is Load.Failed -> Message(s.message, modifier)
        is Load.Ready -> SelectionContainer(modifier.fillMaxSize()) {
            Text(
                s.value.ifEmpty { "(This document has no text.)" },
                color = Palette.Text,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontFamily = if (DocOps.isDocx(file)) FontFamily.Default else FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Palette.Ring)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

// ---------- Zip ----------

@Composable
private fun ZipList(file: InputFile, modifier: Modifier) {
    val context = LocalContext.current
    val state by produceState<Load<List<Pair<String, Long>>>>(Load.Busy, file.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { Load.Ready(ArchiveOps.list(context, file)) }.getOrElse { Load.Failed("Cannot read this zip file.") }
        }
    }
    when (val s = state) {
        is Load.Busy -> Loading(modifier)
        is Load.Failed -> Message(s.message, modifier)
        is Load.Ready -> LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text("${s.value.size} files inside", color = Palette.Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            items(s.value) { (name, size) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(name, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.padding(4.dp))
                    Text(formatSize(size), color = Palette.Muted, fontSize = 13.sp)
                }
            }
        }
    }
}
