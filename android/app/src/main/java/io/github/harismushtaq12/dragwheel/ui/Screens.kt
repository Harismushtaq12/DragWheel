package io.github.harismushtaq12.dragwheel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import io.github.harismushtaq12.dragwheel.Screen
import io.github.harismushtaq12.dragwheel.WheelViewModel
import io.github.harismushtaq12.dragwheel.core.ActionResult
import io.github.harismushtaq12.dragwheel.core.Catalog
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.formatSize
import io.github.harismushtaq12.dragwheel.core.formatTime
import io.github.harismushtaq12.dragwheel.ops.DocOps
import io.github.harismushtaq12.dragwheel.ops.ImageOps
import io.github.harismushtaq12.dragwheel.ops.PdfEditOps
import io.github.harismushtaq12.dragwheel.ops.PdfOps
import io.github.harismushtaq12.dragwheel.core.Mode

/** Opens or shares files in other apps. Implemented by the activity. */
interface Outside {
    fun chooseFiles()
    fun openElsewhere(uri: android.net.Uri, mime: String, name: String)
    fun share(files: List<SavedFile>)
}

@Composable
fun DragWheelApp(vm: WheelViewModel, outside: Outside) {
    val context = LocalContext.current
    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.toast = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .systemBarsPadding()
    ) {
        when (val s = vm.screen) {
            is Screen.Home -> HomeScreen(outside::chooseFiles)
            is Screen.Wheel -> WheelScreen(vm)
            is Screen.View -> ViewScreen(s.file, onBack = { vm.back() }, outside = outside)
            is Screen.ImageEdit -> ImageEditorScreen(s.file, s.state, onBack = { vm.back() }) { edit, ext, quality ->
                vm.runEditor("Applying your changes", "Edited photo") { c ->
                    ActionResult(outputs = listOf(ImageOps.edit(c, s.file, edit, ext, quality)))
                }
            }
            is Screen.PdfPages -> PdfPagesScreen(
                s.file,
                s.state,
                onBack = { vm.back() },
                onSavePdf = { pages ->
                    vm.runEditor("Making the new PDF", "New PDF") { c ->
                        ActionResult(outputs = listOf(PdfOps.organize(c, s.file, pages)))
                    }
                },
                onImages = { chosen, png ->
                    vm.runEditor("Making pictures", "Pages as pictures") { c ->
                        ActionResult(outputs = PdfOps.toImages(c, s.file, png, 150) { chosen })
                    }
                },
            )
            is Screen.TextEdit -> TextEditorScreen(s.doc, s.state, onBack = { vm.back() }) { name, text, format ->
                vm.runEditor("Making your file", "Document ready") { c ->
                    ActionResult(
                        outputs = listOf(
                            when (format) {
                                "docx" -> DocOps.toDocx(c, name, text)
                                "txt" -> DocOps.toTxt(c, name, text)
                                else -> DocOps.toPdf(c, name, text)
                            }
                        )
                    )
                }
            }
            is Screen.PdfEdit -> PdfEditScreen(s.file, s.state, onBack = { vm.back() }) { marks ->
                vm.runEditor("Saving your changes into the PDF", "Edited PDF") { c ->
                    ActionResult(outputs = listOf(PdfEditOps.applyMarks(c, s.file, marks)))
                }
            }
            is Screen.PdfForm -> FormFillScreen(s.file.name, s.state, onBack = { vm.back() }) {
                val values = s.state.values.toMap()
                val flatten = s.state.flatten
                vm.runEditor("Filling in the form", "Form filled") { c ->
                    ActionResult(outputs = listOf(PdfEditOps.fillForm(c, s.file, values, flatten)))
                }
            }
            is Screen.WordEdit -> WordEditScreen(s.file.name, s.state, onBack = { vm.back() }) {
                val texts = s.state.texts.toList()
                val asPdf = s.state.asPdf
                vm.runEditor("Saving your document", "Edited document") { c ->
                    ActionResult(outputs = listOf(DocOps.saveEditedDocx(c, "${s.file.baseName} edited", s.state.editor, texts, asPdf)))
                }
            }
            is Screen.Result -> ResultScreen(s, vm, outside)
        }

        vm.pending?.let { action ->
            ActionSheet(action, vm.files, vm.info, onDismiss = vm::dismissSheet) { options -> vm.run(action, options) }
        }

        vm.working?.let { label -> WorkingOverlay(label) { vm.back() } }

        vm.error?.let { message ->
            AlertDialog(
                onDismissRequest = vm::dismissError,
                confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK", color = Palette.Mint) } },
                title = { Text("That didn't work") },
                text = { Text(message) },
                containerColor = Palette.Ring,
                titleContentColor = Palette.Text,
                textContentColor = Palette.Text,
            )
        }
    }
}

@Composable
private fun WorkingOverlay(label: String, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink.copy(alpha = 0.85f))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Palette.Mint, modifier = Modifier.size(56.dp))
            Text("$label…", color = Palette.Text, fontSize = 17.sp)
            Text("Keep DragWheel open. Big videos can take a few minutes.", color = Palette.Muted, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            TextButton(onClick = onCancel) { Text("Cancel", color = Palette.Muted) }
        }
    }
}

// ---------- Home ----------

@Composable
private fun HomeScreen(onChoose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("DragWheel", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Convert, view and edit files on your phone. Nothing is uploaded.",
            color = Palette.Muted, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Dial(
                actions = emptyList(), mode = Mode.CONVERT, working = false,
                knobTop = "Tap to", knobMain = "Choose file",
                onPick = {}, onKnobTap = onChoose,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
        }
        PrimaryButton("Choose files", Modifier.fillMaxWidth(), onClick = onChoose)
        Text(
            "You can also share a file to DragWheel from Photos, Files, WhatsApp or any other app.",
            color = Palette.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
    }
}

// ---------- Wheel ----------

@Composable
private fun WheelScreen(vm: WheelViewModel) {
    val files = vm.files
    val single = files.size == 1
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("DragWheel", null, onBack = { vm.back() }) {
            TextButton(onClick = vm::clear) { Text("Clear", color = Palette.Muted) }
        }
        FileCard(files, vm.info, onView = if (single) ({ vm.open(Screen.View(files[0])) }) else null)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Dial(
                actions = vm.actions,
                mode = vm.mode,
                working = vm.working != null,
                knobTop = if (files.size > 1) "${files.size} files" else files.firstOrNull()?.ext?.uppercase()?.ifEmpty { "File" } ?: "",
                knobMain = vm.mode.label,
                onPick = { i -> vm.actions.getOrNull(i)?.let(vm::pick) },
                onKnobTap = { vm.nextMode() },
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
        }

        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Palette.Ring)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (m in Mode.entries) Pill(m.label, vm.mode == m) { vm.setMode(m) }
        }
        Text(
            if (vm.actions.isEmpty()) "Nothing to ${vm.mode.label.lowercase()} for this file. Try another ring."
            else "Tap a slice to see what it does. Nothing happens until you confirm.",
            color = Palette.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun FileCard(files: List<InputFile>, info: io.github.harismushtaq12.dragwheel.core.FileInfo?, onView: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Ring)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (files.size == 1) files[0].name else "${files.size} files",
                color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val details = if (files.size == 1) describe(files[0], info)
            else files.joinToString(", ") { it.name }
            Text(details, color = Palette.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onView != null) {
            Spacer(Modifier.size(8.dp))
            SmallButton("View", onClick = onView)
        }
    }
}

private fun describe(f: InputFile, info: io.github.harismushtaq12.dragwheel.core.FileInfo?): String {
    val parts = mutableListOf<String>()
    parts += when (Catalog.kindOf(f)) {
        Catalog.Kind.IMAGE -> "Picture"
        Catalog.Kind.VIDEO -> "Video"
        Catalog.Kind.AUDIO -> "Audio"
        Catalog.Kind.PDF -> "PDF"
        Catalog.Kind.WORD -> "Word document"
        Catalog.Kind.TEXT -> "Text document"
        Catalog.Kind.ZIP -> "Zip archive"
        Catalog.Kind.OTHER -> f.ext.uppercase().ifEmpty { "File" }
    }
    formatSize(f.size).takeIf { it.isNotEmpty() }?.let { parts += it }
    info?.pages?.let { parts += "$it pages" }
    info?.durationMs?.let { parts += formatTime(it) }
    if (info?.width != null && info.height != null) parts += "${info.width}×${info.height}"
    return parts.joinToString(", ")
}

// ---------- View ----------

@Composable
private fun ViewScreen(file: InputFile, onBack: () -> Unit, outside: Outside) {
    Column(Modifier.fillMaxSize()) {
        TopBar(file.name, null, onBack = onBack) {
            TextButton(onClick = { outside.openElsewhere(file.uri, file.mime, file.name) }) {
                Text("Open in…", color = Palette.Muted)
            }
        }
        FileViewer(file, Modifier.weight(1f).fillMaxWidth())
    }
}

// ---------- Result ----------

@Composable
private fun ResultScreen(r: Screen.Result, vm: WheelViewModel, outside: Outside) {
    val outputs = r.result.outputs
    val saved = r.saved != null
    Column(Modifier.fillMaxSize()) {
        TopBar(r.title, if (outputs.isEmpty()) null else if (saved) "Saved in Downloads/DragWheel" else "Not saved yet. Check it, then save.",
            onBack = { vm.back() })

        when {
            outputs.size == 1 -> FileViewer(outputs[0].asInput(), Modifier.weight(1f).fillMaxWidth())
            outputs.size > 1 -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text("${outputs.size} files. Tap one to look at it.", color = Palette.Muted, fontSize = 13.sp)
                }
                items(outputs) { out ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Palette.Ring)
                            .clickable { vm.open(Screen.View(out.asInput())) }
                            .padding(14.dp),
                    ) {
                        Text(out.name, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(formatSize(out.file?.length() ?: -1), color = Palette.Muted, fontSize = 13.sp)
                    }
                }
            }
            else -> {
                val text = r.result.message ?: r.result.clipboard ?: "Done."
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (r.result.clipboard != null) {
                        Text("Copied to the clipboard:", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    SelectionContainer {
                        Text(
                            text, color = Palette.Text, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Palette.Ring)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        )
                    }
                }
            }
        }

        if (outputs.isNotEmpty()) {
            val one = outputs.singleOrNull()
            val sizeLine = buildString {
                val total = outputs.sumOf { it.file?.length() ?: 0L }
                append(formatSize(total))
                val before = vm.files.sumOf { it.size.coerceAtLeast(0) }
                val sameKind = vm.files.size == 1 && outputs.size == 1 &&
                    Catalog.kindOf(vm.files[0]) == Catalog.kindOf(outputs[0].asInput())
                if (before > 0 && total > 0 && sameKind) {
                    val pct = (total * 100 / before).toInt()
                    append(if (pct < 100) ", ${100 - pct}% smaller than the original" else if (pct > 100) ", ${pct - 100}% bigger than the original" else "")
                }
            }
            Text(sizeLine, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Share", Modifier.weight(1f)) { outside.share(r.saved ?: outputs) }
                PrimaryButton(if (saved) "Saved" else "Save to Downloads", Modifier.weight(1.4f), enabled = !saved) { vm.saveResult(r) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (one != null) {
                    TextButton(onClick = { outside.openElsewhere(one.uri, one.mime, one.name) }) { Text("Open in…", color = Palette.Muted) }
                    TextButton(onClick = { vm.continueWith(one) }) { Text("Keep working on it", color = Palette.Muted) }
                }
                TextButton(onClick = { vm.clear() }) { Text("Start over", color = Palette.Muted) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                r.result.clipboard?.let { c -> SecondaryButton("Copy again", Modifier.weight(1f)) { vm.copy(c); vm.toast = "Copied" } }
                PrimaryButton("Done", Modifier.weight(1f)) { vm.back() }
            }
        }
    }
}
