@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.harismushtaq12.dragwheel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.harismushtaq12.dragwheel.core.FileInfo
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.OptionSpec
import io.github.harismushtaq12.dragwheel.core.Options
import io.github.harismushtaq12.dragwheel.core.TimeRange
import io.github.harismushtaq12.dragwheel.core.WheelAction
import io.github.harismushtaq12.dragwheel.core.formatSize
import io.github.harismushtaq12.dragwheel.core.formatTime

/**
 * Explains an action before it runs, lets the user set options,
 * and reminds them that nothing is saved until they choose to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionSheet(
    action: WheelAction,
    files: List<InputFile>,
    info: FileInfo?,
    onDismiss: () -> Unit,
    onRun: (Options) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val values = remember(action, info?.durationMs) {
        mutableStateMapOf<String, Any>().apply {
            for (o in action.options) when (o) {
                is OptionSpec.Choice -> put(o.key, o.default)
                is OptionSpec.Slider -> put(o.key, o.default)
                is OptionSpec.Text -> put(o.key, "")
                is OptionSpec.Range -> info?.durationMs?.let { put(o.key, TimeRange(0, it)) }
            }
        }
    }

    val missing = action.options.firstOrNull { o ->
        when (o) {
            is OptionSpec.Text -> o.required && (values[o.key] as? String).isNullOrBlank()
            is OptionSpec.Range -> values[o.key] == null
            else -> false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Palette.Ring) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(action.button, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(action.description, color = Palette.Text, fontSize = 15.sp, lineHeight = 22.sp)

            val what = if (files.size == 1) files[0].name + (formatSize(files[0].size).let { if (it.isEmpty()) "" else "  ($it)" })
            else "${files.size} files"
            Text("File: $what", color = Palette.Muted, fontSize = 13.sp)

            for (o in action.options) {
                when (o) {
                    is OptionSpec.Choice -> Column {
                        Text(o.label, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((value, label) in o.choices) {
                                Pill(label, values[o.key] == value) { values[o.key] = value }
                            }
                        }
                    }
                    is OptionSpec.Slider -> {
                        val v = values[o.key] as? Float ?: o.default
                        LabeledSlider(o.label, v, o.min..o.max, o.format(v), steps = o.steps) { values[o.key] = it }
                    }
                    is OptionSpec.Text -> { val help = o.help; OutlinedTextField(
                        value = values[o.key] as? String ?: "",
                        onValueChange = { values[o.key] = it },
                        label = { Text(o.label) },
                        placeholder = { Text(o.placeholder) },
                        supportingText = if (help != null) { { Text(help) } } else null,
                        singleLine = true,
                        visualTransformation = if (o.password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (o.password) KeyboardType.Password else KeyboardType.Text),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text,
                            focusedBorderColor = Palette.Mint, unfocusedBorderColor = Palette.Segment,
                            cursorColor = Palette.Mint, focusedLabelColor = Palette.Mint, unfocusedLabelColor = Palette.Muted,
                            focusedPlaceholderColor = Palette.Muted, unfocusedPlaceholderColor = Palette.Muted,
                            focusedSupportingTextColor = Palette.Muted, unfocusedSupportingTextColor = Palette.Muted,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) }
                    is OptionSpec.Range -> {
                        val total = info?.durationMs
                        if (total == null || total <= 0) {
                            Text(
                                if (info == null) "Reading the length of this file…"
                                else "Could not read the length of this file, so it cannot be trimmed.",
                                color = Palette.Muted, fontSize = 14.sp,
                            )
                        } else {
                            val r = values[o.key] as? TimeRange ?: TimeRange(0, total)
                            Column {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(o.label, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    Text(
                                        "${formatTime(r.startMs)} to ${formatTime(r.endMs)}  (${formatTime(r.endMs - r.startMs)})",
                                        color = Palette.Mint, fontSize = 14.sp,
                                    )
                                }
                                RangeSlider(
                                    value = r.startMs.toFloat()..r.endMs.toFloat(),
                                    onValueChange = { values[o.key] = TimeRange(it.start.toLong(), it.endInclusive.toLong()) },
                                    valueRange = 0f..total.toFloat(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Palette.Mint, activeTrackColor = Palette.Mint,
                                        inactiveTrackColor = Palette.Segment,
                                    ),
                                )
                                Text("Drag the two dots to choose the start and the end.", color = Palette.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Text(
                "Your original file is not changed. You will see the result first, and it is only saved when you tap Save.",
                color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp,
            )
            PrimaryButton(
                if (missing != null) "Fill in ${missing.label.lowercase()}" else action.button,
                Modifier.fillMaxWidth(),
                enabled = missing == null,
            ) { onRun(values.toMap()) }
        }
    }
}
