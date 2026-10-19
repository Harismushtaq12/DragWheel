package io.github.harismushtaq12.dragwheel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.harismushtaq12.dragwheel.Status
import io.github.harismushtaq12.dragwheel.WheelViewModel
import io.github.harismushtaq12.dragwheel.core.SavedFile

@Composable
fun DragWheelScreen(
    vm: WheelViewModel,
    onChooseFiles: () -> Unit,
    onOpen: (SavedFile) -> Unit,
    onShare: (List<SavedFile>) -> Unit,
) {
    val files = vm.files
    val status = vm.status
    val working = status is Status.Working
    val hasFiles = files.isNotEmpty()

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .systemBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("DragWheel", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (hasFiles && !working) {
                    TextButton(onClick = vm::clear) { Text("Clear", color = Palette.Muted) }
                }
            }

            Text(
                text = when {
                    !hasFiles -> "Convert and edit files on your phone. Nothing is uploaded."
                    files.size == 1 -> files[0].name
                    else -> "${files.size} files: " + files.joinToString(", ") { it.name }
                },
                color = Palette.Muted,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Dial(
                    actions = vm.actions,
                    toolsMode = vm.toolsMode,
                    working = working,
                    knobTop = when {
                        working -> "Working"
                        !hasFiles -> ""
                        files.size > 1 -> "${files.size} files"
                        else -> files[0].ext.uppercase().ifEmpty { "File" }
                    },
                    knobMain = when {
                        status is Status.Working -> status.title
                        !hasFiles -> "Choose"
                        vm.toolsMode -> "Tools"
                        else -> "Convert"
                    },
                    onPick = { i -> vm.actions.getOrNull(i)?.let(vm::run) },
                    onKnobTap = { if (hasFiles) vm.setMode(!vm.toolsMode) else onChooseFiles() },
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                )
            }

            if (hasFiles) {
                ModeSwitch(tools = vm.toolsMode, enabled = !working, onChange = vm::setMode)
                Text(
                    text = if (working) "Working on it. Keep DragWheel open."
                    else "Tap a slice, or press the centre and slide to one.",
                    color = Palette.Muted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
            } else {
                Button(
                    onClick = onChooseFiles,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint, contentColor = Palette.OnMint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) { Text("Choose files", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Text(
                    "You can also share a file to DragWheel from Photos, Files or any other app.",
                    color = Palette.Muted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
            }
        }

        when (status) {
            is Status.Done -> ResultCard(
                title = status.title,
                body = resultText(status),
                outputs = status.result.outputs,
                onOpen = onOpen,
                onShare = onShare,
                onClose = vm::dismiss,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            is Status.Failed -> ResultCard(
                title = "${status.title} didn't work",
                body = status.message,
                outputs = emptyList(),
                onOpen = onOpen,
                onShare = onShare,
                onClose = vm::dismiss,
                isError = true,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            else -> Unit
        }
    }
}

private fun resultText(done: Status.Done): String {
    val r = done.result
    return when {
        r.outputs.size == 1 -> "Saved ${r.outputs[0].name} to Downloads/DragWheel."
        r.outputs.size > 1 -> "Saved ${r.outputs.size} files to Downloads/DragWheel."
        r.clipboard != null -> "Copied to clipboard:\n${r.clipboard}"
        else -> r.message ?: ""
    }
}

@Composable
private fun ModeSwitch(tools: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.Ring)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Pill("Convert", selected = !tools, enabled = enabled) { onChange(false) }
        Pill("Tools", selected = tools, enabled = enabled) { onChange(true) }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Palette.KnobText else Palette.Muted,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Palette.Knob else Palette.Ring)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    )
}

@Composable
private fun ResultCard(
    title: String,
    body: String,
    outputs: List<SavedFile>,
    onOpen: (SavedFile) -> Unit,
    onShare: (List<SavedFile>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Surface(
        color = Palette.Ring,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, color = if (isError) Palette.Error else Palette.Mint, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                body,
                color = Palette.Text,
                fontSize = 14.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (outputs.isNotEmpty()) {
                    Button(
                        onClick = { onOpen(outputs[0]) },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint, contentColor = Palette.OnMint),
                    ) { Text("Open") }
                    OutlinedButton(onClick = { onShare(outputs) }) { Text("Share", color = Palette.Text) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(if (isError) "OK" else "Done", color = Palette.Muted) }
            }
        }
    }
}
