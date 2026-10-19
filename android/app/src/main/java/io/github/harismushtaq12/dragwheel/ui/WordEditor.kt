@file:OptIn(ExperimentalLayoutApi::class)

package io.github.harismushtaq12.dragwheel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text,
    focusedBorderColor = Palette.Mint, unfocusedBorderColor = Palette.Segment,
    cursorColor = Palette.Mint, focusedLabelColor = Palette.Mint, unfocusedLabelColor = Palette.Muted,
    focusedPlaceholderColor = Palette.Muted, unfocusedPlaceholderColor = Palette.Muted,
    focusedContainerColor = Palette.Ring, unfocusedContainerColor = Palette.Ring,
)

/**
 * Edit the words of a Word document, paragraph by paragraph.
 * Only changed words are rewritten, so fonts, bold, colours, tables and pictures stay as they were.
 */
@Composable
fun WordEditScreen(fileName: String, state: WordEditState, onBack: () -> Unit, onSave: () -> Unit) {
    val texts = state.texts
    val matches = if (state.find.isEmpty()) 0 else texts.sumOf { t ->
        var n = 0
        var i = t.indexOf(state.find)
        while (i >= 0) { n++; i = t.indexOf(state.find, i + state.find.length) }
        n
    }
    val changed = texts.indices.count { texts[it] != state.editor.texts.getOrNull(it) }

    EditorScaffold(
        title = "Edit Word",
        subtitle = if (changed == 0) "Formatting is kept. Tap any paragraph to change it." else "$changed paragraphs changed. Formatting is kept.",
        onBack = onBack,
        primary = "Preview result",
        onPrimary = onSave,
    ) {
        // Find and replace
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.find, onValueChange = { state.find = it }, singleLine = true,
                placeholder = { Text("Find") }, colors = fieldColors(), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.replace, onValueChange = { state.replace = it }, singleLine = true,
                placeholder = { Text("Replace with") }, colors = fieldColors(), modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.find.isEmpty()) "Find words in the whole document." else "$matches found",
                color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            SmallButton("Replace all", enabled = matches > 0) {
                for (i in texts.indices) if (texts[i].contains(state.find)) texts[i] = texts[i].replace(state.find, state.replace)
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(texts) { i, t ->
                OutlinedTextField(
                    value = t,
                    onValueChange = { texts[i] = it },
                    placeholder = { Text("(empty line)", fontSize = 12.sp) },
                    textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = Palette.Text),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Save as", color = Palette.Muted, fontSize = 13.sp)
            Pill("Word (same look)", !state.asPdf) { state.asPdf = false }
            Pill("PDF", state.asPdf) { state.asPdf = true }
        }
        Text(fileName, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/** Fill in the fields of a PDF form. */
@Composable
fun FormFillScreen(fileName: String, state: FormState, onBack: () -> Unit, onSave: () -> Unit) {
    EditorScaffold(
        title = "Fill form",
        subtitle = "${state.fields.size} fields in $fileName",
        onBack = onBack,
        primary = "Preview result",
        onPrimary = onSave,
    ) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(state.fields) { _, f ->
                val label = f.name.substringAfterLast('.').replace('_', ' ')
                when (f.kind) {
                    0 -> OutlinedTextField(
                        value = state.values[f.name] ?: "",
                        onValueChange = { state.values[f.name] = it },
                        label = { Text(label) },
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.values[f.name] == "true",
                            onCheckedChange = { state.values[f.name] = if (it) "true" else "false" },
                            colors = CheckboxDefaults.colors(checkedColor = Palette.Mint, checkmarkColor = Palette.OnMint, uncheckedColor = Palette.Muted),
                        )
                        Text(label, color = Palette.Text, fontSize = 15.sp)
                    }
                    else -> Column {
                        Text(label, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (o in f.options) Pill(o, state.values[f.name] == o) { state.values[f.name] = o }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Keep editable", !state.flatten) { state.flatten = false }
                    Pill("Lock answers", state.flatten) { state.flatten = true }
                }
                Text(
                    "Lock answers turns the fields into normal text, so nobody can change them later.",
                    color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
