package io.github.harismushtaq12.dragwheel.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.ops.BoxMark
import io.github.harismushtaq12.dragwheel.ops.InkMark
import io.github.harismushtaq12.dragwheel.ops.Mark
import io.github.harismushtaq12.dragwheel.ops.PdfEditOps
import io.github.harismushtaq12.dragwheel.ops.TextLine
import io.github.harismushtaq12.dragwheel.ops.TextMark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the text dialog is editing. */
private data class TextDraft(
    val page: Int, val x: Float, val y: Float,
    val text: String, val size: Float, val bold: Boolean,
    val cover: List<Float>?,
    val replacing: TextMark?,
    /** For changed text: where the old line sat (0..1 from the top), so the new words sit on the same line. */
    val baseline: Float? = null,
    /** Page height in points, to turn the font size into the same 0..1 units. */
    val pageHeightPt: Float = 842f,
)

/**
 * Edit the PDF itself. New things are drawn on top of the page, so everything
 * else in the PDF (fonts, pictures, layout) stays exactly as it was.
 */
@Composable
fun PdfEditScreen(file: InputFile, state: PdfEditState, onBack: () -> Unit, onSave: (List<Mark>) -> Unit) {
    val context = LocalContext.current
    val sourceState = rememberPdfSource(file)
    val lines by produceState<Map<Int, List<TextLine>>>(emptyMap(), file.uri) {
        value = withContext(Dispatchers.IO) { runCatching { PdfEditOps.textLines(context, file) }.getOrDefault(emptyMap()) }
    }
    var draft by remember { mutableStateOf<TextDraft?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    EditorScaffold(
        title = "Edit PDF",
        subtitle = note ?: state.tool.help,
        onBack = onBack,
        primary = "Preview result",
        primaryEnabled = state.marks.isNotEmpty(),
        onPrimary = { onSave(state.marks.toList()) },
        secondary = "Undo",
        onSecondary = { if (state.marks.isNotEmpty()) state.marks.removeAt(state.marks.lastIndex) },
    ) {
        // Tools
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (t in PdfTool.entries) Pill(t.label, state.tool == t) { state.tool = t; note = null }
        }
        if (state.tool == PdfTool.DRAW || state.tool == PdfTool.ADD_TEXT) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Colour", color = Palette.Muted, fontSize = 13.sp)
                for ((label, c) in listOf("Black" to android.graphics.Color.BLACK, "Blue" to 0xFF1A47C8.toInt(), "Red" to 0xFFD32F2F.toInt())) {
                    Pill(label, state.color == c) { state.color = c }
                }
            }
        }

        when (sourceState) {
            is Load.Busy -> Message("Loading pages…", Modifier.weight(1f))
            is Load.Failed -> Message(sourceState.message, Modifier.weight(1f))
            is Load.Ready -> {
                val source = sourceState.value
                val scrolling = state.tool == PdfTool.MOVE || state.tool == PdfTool.EDIT_TEXT || state.tool == PdfTool.ADD_TEXT
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        userScrollEnabled = scrolling,
                    ) {
                        items(source.pageCount) { page ->
                            val (pw, ph) = source.sizes[page]
                            Box(Modifier.fillMaxWidth().aspectRatio((pw.toFloat() / ph).coerceIn(0.2f, 5f))) {
                                PdfPageImage(source, page, widthPx, Modifier.fillMaxSize())
                                PageOverlay(
                                    page = page,
                                    pageWidthPt = pw.toFloat(),
                                    state = state,
                                    lines = lines[page].orEmpty(),
                                    onTapText = { u, v ->
                                        note = null
                                        if (state.tool == PdfTool.ADD_TEXT) {
                                            draft = TextDraft(page, u, v, "", 12f, false, null, null)
                                        } else {
                                            val line = lines[page].orEmpty().firstOrNull { l ->
                                                u >= l.l - 0.01f && u <= l.r + 0.01f && v >= l.t - 0.006f && v <= l.b + 0.006f
                                            }
                                            if (line == null) {
                                                note = "No text found there. Tap right on the words, or use Add text."
                                            } else {
                                                val cover = listOf(line.l - 0.003f, line.t - 0.003f, line.r + 0.003f, line.b + 0.004f)
                                                val existing = state.marks.filterIsInstance<TextMark>().lastOrNull { it.page == page && it.cover == cover }
                                                draft = TextDraft(
                                                    page, line.l, line.t,
                                                    existing?.text ?: line.text,
                                                    existing?.sizePt ?: line.sizePt,
                                                    existing?.bold ?: line.bold,
                                                    cover, existing,
                                                    baseline = line.b,
                                                    pageHeightPt = ph.toFloat(),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    draft?.let { d ->
        TextDialog(
            draft = d,
            onDismiss = { draft = null },
            onApply = { text, size, bold ->
                d.replacing?.let { state.marks.remove(it) }
                if (text.isNotBlank() || d.cover != null) {
                    // Text is placed by its top; keep the old baseline so the line does not jump.
                    val y = d.baseline?.let { it - size / d.pageHeightPt } ?: d.y
                    state.marks += TextMark(d.page, d.x, y, text, size, bold, if (d.cover != null) android.graphics.Color.BLACK else state.color, d.cover)
                }
                draft = null
            },
        )
    }
}

@Composable
private fun PageOverlay(
    page: Int,
    pageWidthPt: Float,
    state: PdfEditState,
    lines: List<TextLine>,
    onTapText: (Float, Float) -> Unit,
) {
    var stroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val tool = state.tool

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(tool, page) {
                when (tool) {
                    PdfTool.EDIT_TEXT, PdfTool.ADD_TEXT -> detectTapGestures { p ->
                        onTapText(p.x / size.width, p.y / size.height)
                    }
                    PdfTool.DRAW -> detectDragGestures(
                        onDragStart = { p -> stroke = listOf(p) },
                        onDragEnd = {
                            if (stroke.size > 1) state.marks += InkMark(
                                page,
                                stroke.map { it.x / size.width to it.y / size.height },
                                state.color, 0.004f,
                            )
                            stroke = emptyList()
                        },
                        onDragCancel = { stroke = emptyList() },
                        onDrag = { change, _ ->
                            change.consume()
                            stroke = stroke + change.position
                        },
                    )
                    PdfTool.HIGHLIGHT, PdfTool.WHITE_OUT -> detectDragGestures(
                        onDragStart = { p -> dragStart = p; dragEnd = p },
                        onDragEnd = {
                            val a = dragStart
                            val b = dragEnd
                            if (a != null && b != null && abs(a.x - b.x) > 4 && abs(a.y - b.y) > 4) {
                                state.marks += BoxMark(
                                    page,
                                    min(a.x, b.x) / size.width, min(a.y, b.y) / size.height,
                                    max(a.x, b.x) / size.width, max(a.y, b.y) / size.height,
                                    highlight = tool == PdfTool.HIGHLIGHT,
                                )
                            }
                            dragStart = null; dragEnd = null
                        },
                        onDragCancel = { dragStart = null; dragEnd = null },
                        onDrag = { change, _ ->
                            change.consume()
                            dragEnd = change.position
                        },
                    )
                    PdfTool.MOVE -> Unit
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val pxPerPt = w / pageWidthPt

        // Show what can be tapped.
        if (tool == PdfTool.EDIT_TEXT) {
            for (l in lines) drawRect(
                Palette.Mint.copy(alpha = 0.55f), Offset(l.l * w, l.t * h), Size((l.r - l.l) * w, (l.b - l.t) * h),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        for (m in state.marks) {
            if (m.page != page) continue
            when (m) {
                is BoxMark -> drawRect(
                    if (m.highlight) Color(0xFFFFEB33).copy(alpha = 0.35f) else Color.White,
                    Offset(m.l * w, m.t * h), Size((m.r - m.l) * w, (m.b - m.t) * h),
                )
                is InkMark -> {
                    val path = Path()
                    m.points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h) }
                    drawPath(path, Color(m.color), style = Stroke(width = max(1f, m.width * w), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                is TextMark -> {
                    m.cover?.let { c -> drawRect(Color.White, Offset(c[0] * w, c[1] * h), Size((c[2] - c[0]) * w, (c[3] - c[1]) * h)) }
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = m.color
                        textSize = m.sizePt * pxPerPt
                        typeface = if (m.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                    drawIntoCanvas { canvas ->
                        m.text.split('\n').forEachIndexed { i, line ->
                            canvas.nativeCanvas.drawText(line, m.x * w, m.y * h + paint.textSize * (1f + i * 1.25f), paint)
                        }
                    }
                }
            }
        }

        // What is being drawn right now.
        if (stroke.size > 1) {
            val path = Path()
            stroke.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            drawPath(path, Color(state.color), style = Stroke(width = 0.004f * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val a = dragStart
        val b = dragEnd
        if (a != null && b != null) {
            val tl = Offset(min(a.x, b.x), min(a.y, b.y))
            val sz = Size(abs(a.x - b.x), abs(a.y - b.y))
            drawRect(if (tool == PdfTool.HIGHLIGHT) Color(0xFFFFEB33).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.85f), tl, sz)
            drawRect(Palette.Mint, tl, sz, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

@Composable
private fun TextDialog(draft: TextDraft, onDismiss: () -> Unit, onApply: (String, Float, Boolean) -> Unit) {
    var text by remember(draft) { mutableStateOf(draft.text) }
    var size by remember(draft) { mutableStateOf(draft.size) }
    var bold by remember(draft) { mutableStateOf(draft.bold) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Ring,
        titleContentColor = Palette.Text,
        textContentColor = Palette.Text,
        title = { Text(if (draft.cover != null) "Change text" else "Add text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text,
                        focusedBorderColor = Palette.Mint, unfocusedBorderColor = Palette.Segment, cursorColor = Palette.Mint,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledSlider("Size", size, 6f..48f, "${size.roundToInt()} pt") { size = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Normal", !bold) { bold = false }
                    Pill("Bold", bold) { bold = true }
                }
                if (draft.cover != null) {
                    Text(
                        "The old words are covered with white and your words are written on top, in the phone's font.",
                        color = Palette.Muted, fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(text, size, bold) }) { Text("Apply", color = Palette.Mint) } },
        dismissButton = {
            Row {
                if (draft.cover != null) TextButton(onClick = { onApply("", size, bold) }) { Text("Delete words", color = Palette.Error) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Muted) }
            }
        },
    )
}
