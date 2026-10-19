package io.github.harismushtaq12.dragwheel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.ops.PageEdit

/**
 * All pages of a PDF as a grid. Pick pages, then rotate, delete or move them,
 * save a new PDF, or turn the picked pages (or all) into pictures.
 */
@Composable
fun PdfPagesScreen(
    file: InputFile,
    state: PagesEditState,
    onBack: () -> Unit,
    onSavePdf: (List<PageEdit>) -> Unit,
    onImages: (pages: List<PageEdit>, png: Boolean) -> Unit,
) {
    val sourceState = rememberPdfSource(file)
    val pages = state.pages
    val selected = state.selected
    var png by state::png
    var note by remember { mutableStateOf<String?>(null) }

    val source = (sourceState as? Load.Ready)?.value
    LaunchedEffect(source) {
        if (source != null && pages.isEmpty()) pages.addAll((0 until source.pageCount).map { PageEdit(it) })
    }

    val changed = pages.size != (source?.pageCount ?: 0) ||
        pages.withIndex().any { (i, p) -> p.source != i || p.extraRotation != 0 }

    EditorScaffold(
        title = "Pages",
        subtitle = "${file.name}  (${pages.size} pages)",
        onBack = onBack,
        primary = "Save new PDF",
        primaryEnabled = changed && pages.isNotEmpty(),
        onPrimary = { onSavePdf(pages.toList()) },
        secondary = if (selected.isEmpty()) "All to pictures" else "${selected.size} to pictures",
        onSecondary = {
            // Keeps the order and the turns the user made.
            val chosen = if (selected.isEmpty()) pages.toList() else pages.filter { it.source in selected }
            onImages(chosen, png)
        },
    ) {
        when (sourceState) {
            is Load.Busy -> Message("Loading pages…", Modifier.weight(1f))
            is Load.Failed -> Message(sourceState.message, Modifier.weight(1f))
            is Load.Ready -> {
                Text(
                    note ?: if (selected.isEmpty()) "Tap pages to pick them." else "${selected.size} picked. Use the buttons below.",
                    color = Palette.Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(pages, key = { _, p -> p.source }) { index, p ->
                        val isPicked = p.source in selected
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(3.dp, if (isPicked) Palette.Mint else Palette.Ink, RoundedCornerShape(8.dp))
                                .clickable {
                                    note = null
                                    if (isPicked) selected.remove(p.source) else selected.add(p.source)
                                }
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            PdfPageImage(sourceState.value, p.source, 280, Modifier.fillMaxWidth(), quarterTurns = p.extraRotation / 90)
                            Box(
                                Modifier
                                    .padding(top = 4.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isPicked) Palette.Mint else Palette.Segment)
                                    .padding(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "${index + 1}" + if (p.source != index) "  (was ${p.source + 1})" else "",
                                    color = if (isPicked) Palette.OnMint else Palette.Text,
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val any = selected.isNotEmpty()
                    SmallButton(if (selected.size == pages.size) "Pick none" else "Pick all") {
                        if (selected.size == pages.size) selected.clear()
                        else { selected.clear(); selected.addAll(pages.map { it.source }) }
                    }
                    SmallButton("Turn right", any) {
                        for (i in pages.indices) if (pages[i].source in selected)
                            pages[i] = pages[i].copy(extraRotation = (pages[i].extraRotation + 90) % 360)
                    }
                    SmallButton("Move earlier", any) {
                        for (i in 1 until pages.size) {
                            if (pages[i].source in selected && pages[i - 1].source !in selected) {
                                val t = pages[i]; pages[i] = pages[i - 1]; pages[i - 1] = t
                            }
                        }
                    }
                    SmallButton("Move later", any) {
                        for (i in pages.size - 2 downTo 0) {
                            if (pages[i].source in selected && pages[i + 1].source !in selected) {
                                val t = pages[i]; pages[i] = pages[i + 1]; pages[i + 1] = t
                            }
                        }
                    }
                    SmallButton("Delete", any) {
                        if (selected.size >= pages.size) {
                            note = "A PDF needs at least one page."
                        } else {
                            pages.removeAll { it.source in selected }
                            selected.clear()
                        }
                    }
                    SmallButton("Undo all") {
                        pages.clear(); selected.clear()
                        pages.addAll((0 until sourceState.value.pageCount).map { PageEdit(it) })
                    }
                }
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Pictures as", color = Palette.Muted, fontSize = 13.sp)
                    Pill("JPG", !png) { png = false }
                    Pill("PNG", png) { png = true }
                }
            }
        }
    }
}
