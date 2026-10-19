package io.github.harismushtaq12.dragwheel.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Same palette as the desktop wheel: slate-ink ring, light knob, mint for the live choice. */
object Palette {
    val Ink = Color(0xFF141C25)
    val Ring = Color(0xFF1D2733)
    val RingTools = Color(0xFF2A2338)
    val Segment = Color(0xFF2C3A49)
    val SegmentTools = Color(0xFF3A314B)
    val Mint = Color(0xFF7FE0C2)
    val OnMint = Color(0xFF101B17)
    val Knob = Color(0xFFEEF2F5)
    val KnobText = Color(0xFF1D2733)
    val KnobMuted = Color(0xFF5D6B7A)
    val Text = Color(0xFFEEF2F5)
    val Muted = Color(0xFF9AA8B6)
    val Error = Color(0xFFFF9C8A)
}

@Composable
fun DragWheelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Mint,
            onPrimary = Palette.OnMint,
            background = Palette.Ink,
            onBackground = Palette.Text,
            surface = Palette.Ring,
            onSurface = Palette.Text,
            onSurfaceVariant = Palette.Muted,
            error = Palette.Error,
        ),
        content = content,
    )
}
