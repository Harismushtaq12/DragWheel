package io.github.harismushtaq12.dragwheel.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.ops.ImageEdit
import io.github.harismushtaq12.dragwheel.ops.ImageOps
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Grip { NONE, MOVE, TL, TR, BL, BR }

/** Crop, rotate, flip and colour. Nothing is saved until the user presses Preview result. */
@Composable
fun ImageEditorScreen(
    file: InputFile,
    state: ImageEditState,
    onBack: () -> Unit,
    onExport: (ImageEdit, ext: String, quality: Int) -> Unit,
) {
    val context = LocalContext.current
    val loaded by produceState<Load<Bitmap>>(Load.Busy, file.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { Load.Ready(ImageOps.load(context, file, maxSide = 1600)) }
                .getOrElse { Load.Failed("Cannot open this picture.") }
        }
    }
    val original = (loaded as? Load.Ready)?.value

    var turns by state::turns
    var flip by state::flip
    var crop by state::crop
    var brightness by state::brightness
    var contrast by state::contrast
    var saturation by state::saturation
    var tab by state::tab
    var format by state::format

    val shown = remember(original, turns, flip) {
        original?.let { ImageOps.transform(it.copy(Bitmap.Config.ARGB_8888, false), turns, flip).asImageBitmap() }
    }
    val filter = remember(brightness, contrast, saturation) {
        ColorFilter.colorMatrix(ColorMatrix(ImageOps.colorMatrixValues(saturation, contrast, brightness)))
    }

    EditorScaffold(
        title = "Edit photo",
        subtitle = file.name,
        onBack = onBack,
        primary = "Preview result",
        onPrimary = {
            onExport(
                ImageEdit(turns, flip, crop.left, crop.top, crop.right, crop.bottom, brightness, contrast, saturation),
                format, 92,
            )
        },
    ) {
        // Picture with crop frame
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            val img = shown
            if (img == null) {
                Message(if (loaded is Load.Failed) "Cannot open this picture." else "Loading…")
                return@BoxWithConstraints
            }
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val scale = min(boxW / img.width, boxH / img.height)
            val dw = img.width * scale
            val dh = img.height * scale
            val left = (boxW - dw) / 2f
            val top = (boxH - dh) / 2f
            val grip = with(LocalDensity.current) { 36.dp.toPx() }
            var mode by remember { mutableStateOf(Grip.NONE) }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(img, tab, boxW, boxH) {
                        if (tab != 0) return@pointerInput
                        detectDragGestures(
                            onDragStart = { p ->
                                val r = Rect(left + crop.left * dw, top + crop.top * dh, left + crop.right * dw, top + crop.bottom * dh)
                                fun near(x: Float, y: Float) = abs(p.x - x) < grip && abs(p.y - y) < grip
                                mode = when {
                                    near(r.left, r.top) -> Grip.TL
                                    near(r.right, r.top) -> Grip.TR
                                    near(r.left, r.bottom) -> Grip.BL
                                    near(r.right, r.bottom) -> Grip.BR
                                    r.contains(p) -> Grip.MOVE
                                    else -> Grip.NONE
                                }
                            },
                            onDragEnd = { mode = Grip.NONE },
                            onDrag = { change, d ->
                                change.consume()
                                val dx = d.x / dw
                                val dy = d.y / dh
                                val minSize = 0.08f
                                var (l, t, r, b) = listOf(crop.left, crop.top, crop.right, crop.bottom)
                                when (mode) {
                                    Grip.MOVE -> {
                                        val w = r - l
                                        val h = b - t
                                        l = (l + dx).coerceIn(0f, 1f - w); t = (t + dy).coerceIn(0f, 1f - h)
                                        r = l + w; b = t + h
                                    }
                                    Grip.TL -> { l = (l + dx).coerceIn(0f, r - minSize); t = (t + dy).coerceIn(0f, b - minSize) }
                                    Grip.TR -> { r = (r + dx).coerceIn(l + minSize, 1f); t = (t + dy).coerceIn(0f, b - minSize) }
                                    Grip.BL -> { l = (l + dx).coerceIn(0f, r - minSize); b = (b + dy).coerceIn(t + minSize, 1f) }
                                    Grip.BR -> { r = (r + dx).coerceIn(l + minSize, 1f); b = (b + dy).coerceIn(t + minSize, 1f) }
                                    Grip.NONE -> Unit
                                }
                                crop = Rect(l, t, r, b)
                            },
                        )
                    }
            ) {
                drawImage(
                    image = img,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                    colorFilter = filter,
                )
                val r = Rect(left + crop.left * dw, top + crop.top * dh, left + crop.right * dw, top + crop.bottom * dh)
                val shade = Color.Black.copy(alpha = 0.55f)
                drawRect(shade, Offset(left, top), Size(dw, r.top - top))
                drawRect(shade, Offset(left, r.bottom), Size(dw, top + dh - r.bottom))
                drawRect(shade, Offset(left, r.top), Size(r.left - left, r.height))
                drawRect(shade, Offset(r.right, r.top), Size(left + dw - r.right, r.height))
                drawRect(Color.White, r.topLeft, r.size, style = Stroke(width = 2.dp.toPx()))
                if (tab == 0) {
                    for (c in listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight)) {
                        drawCircle(Palette.Mint, radius = 9.dp.toPx(), center = c)
                    }
                }
            }
        }

        // Tabs
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Crop & turn", tab == 0) { tab = 0 }
            Pill("Light & colour", tab == 1) { tab = 1 }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (tab == 0) {
                Text("Drag the corners to crop. Drag inside the frame to move it.", color = Palette.Muted, fontSize = 13.sp)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Turn left") { turns -= 1; crop = Rect(0f, 0f, 1f, 1f) }
                    SmallButton("Turn right") { turns += 1; crop = Rect(0f, 0f, 1f, 1f) }
                    SmallButton("Flip") { flip = !flip; crop = Rect(0f, 0f, 1f, 1f) }
                    SmallButton("Reset") { crop = Rect(0f, 0f, 1f, 1f) }
                }
            } else {
                LabeledSlider("Brightness", brightness, -100f..100f, "${brightness.roundToInt()}") { brightness = it }
                LabeledSlider("Contrast", contrast, 0.5f..1.5f, "${(contrast * 100).roundToInt()}%") { contrast = it }
                LabeledSlider("Colour", saturation, 0f..2f, if (saturation == 0f) "none" else "${(saturation * 100).roundToInt()}%") { saturation = it }
                SmallButton("Reset colours") { brightness = 0f; contrast = 1f; saturation = 1f }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save as", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.width(4.dp))
                for ((ext, label) in listOf("jpg" to "JPG", "png" to "PNG", "webp" to "WebP")) {
                    Pill(label, format == ext) { format = ext }
                }
            }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    shown: String,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(shown, color = Palette.Mint, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Palette.Mint,
                activeTrackColor = Palette.Mint,
                inactiveTrackColor = Palette.Segment,
                activeTickColor = Palette.OnMint,
                inactiveTickColor = Palette.Muted,
            ),
        )
    }
}
