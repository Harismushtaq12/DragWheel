package io.github.harismushtaq12.dragwheel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.harismushtaq12.dragwheel.core.TextDoc

private const val EDIT_LIMIT = 300_000

/** Edit the words, then save as PDF, Word or TXT. */
@Composable
fun TextEditorScreen(
    doc: TextDoc,
    onBack: () -> Unit,
    onSave: (name: String, text: String, format: String) -> Unit,
) {
    val tooLong = doc.text.length > EDIT_LIMIT
    var text by remember(doc) { mutableStateOf(if (tooLong) doc.text.take(EDIT_LIMIT) else doc.text) }
    var name by remember(doc) { mutableStateOf(doc.baseName) }
    var format by remember { mutableStateOf("pdf") }
    val words = remember(text) { text.split(Regex("\\s+")).count { it.isNotBlank() } }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text,
        focusedBorderColor = Palette.Mint, unfocusedBorderColor = Palette.Segment,
        cursorColor = Palette.Mint, focusedLabelColor = Palette.Mint, unfocusedLabelColor = Palette.Muted,
        focusedContainerColor = Palette.Ring, unfocusedContainerColor = Palette.Ring,
    )

    EditorScaffold(
        title = "Edit text",
        subtitle = "$words words" + if (tooLong) "  (very long: only the first part is shown)" else "",
        onBack = onBack,
        primary = "Preview result",
        primaryEnabled = name.isNotBlank(),
        onPrimary = { onSave(name.trim(), text, format) },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.replace('/', '_') },
            label = { Text("File name") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = Palette.Text),
            colors = fieldColors,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Save as", color = Palette.Muted, fontSize = 13.sp)
            Pill("PDF", format == "pdf") { format = "pdf" }
            Pill("Word", format == "docx") { format = "docx" }
            Pill("TXT", format == "txt") { format = "txt" }
        }
    }
}
