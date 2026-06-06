package yokai.presentation.novel.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelPreferences

/**
 * Result of the chapter-load pipeline: the persisted chapter row id, the resume scroll progress
 * (0..10000 hundredths of a percent), the raw chapter HTML (what the source's `parseChapter`
 * returns), and the paragraph-parsed text. Built by
 * [yokai.presentation.novel.details.NovelDetailsScreenModel]; consumed by [ChapterReader] (paragraphs)
 * and, from Phase 1.2 on, by the WebView reader (rawHtml).
 */
data class ChapterRead(
    val chapterId: Long,
    val initialProgress: Int,
    val rawHtml: String,
    val paragraphs: List<String>,
)

/**
 * Renders parseChapter output as a scrollable column of paragraphs. Progress (first-visible
 * paragraph index, expressed as 0..10000 hundredths of a percent) auto-saves on a 1-second
 * debounce so re-opening the chapter scrolls back to where the user left off. Shared by the novel
 * browse screen and the novel details screen.
 */
@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
fun ChapterReader(
    paragraphs: List<String>,
    chapterId: Long,
    initialProgress: Int,
    chapterRepo: NovelChapterRepository,
    settingsOpen: Boolean,
    onSettingsOpenChange: (Boolean) -> Unit,
) {
    if (paragraphs.isEmpty()) {
        Text("(no readable text in chapter)")
        return
    }
    val prefs = remember { Injekt.get<NovelPreferences>() }
    val fontSize by prefs.readerFontSize().collectAsState()
    val lineSpacing by prefs.readerLineSpacing().collectAsState()
    val themeMode by prefs.readerTheme().collectAsState()

    val systemDark = isSystemInDarkTheme()
    val (bg, fg) = readerColors(themeMode, systemDark)

    val lazyListState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState()

    // Restore scroll position on first composition (or when the chapter changes).
    LaunchedEffect(chapterId, paragraphs.size) {
        val targetIdx = (initialProgress.toLong() * paragraphs.size / 10_000L).toInt()
            .coerceIn(0, paragraphs.lastIndex)
        if (targetIdx > 0) lazyListState.scrollToItem(targetIdx)
    }

    // Auto-save scroll progress while reading.
    LaunchedEffect(chapterId, paragraphs.size) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .debounce(1_000)
            .distinctUntilChanged()
            .collect { idx ->
                val progress = (idx.toLong() * 10_000L / paragraphs.size.coerceAtLeast(1)).toInt()
                chapterRepo.setLastTextProgress(chapterId, progress)
            }
    }

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        SelectionContainer(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                // Key by index, not the paragraph text: duplicate paragraphs (blank lines, scene
                // breaks like "***", repeated phrases) share a String hashCode and would collide,
                // crashing the LazyColumn. The list is read-only and never reorders, so the index is
                // a stable, unique key.
                itemsIndexed(items = paragraphs, key = { index, _ -> index }) { _, p ->
                    Text(
                        text = p,
                        color = fg,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * lineSpacing).sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { onSettingsOpenChange(false) },
            sheetState = sheetState,
        ) {
            ReaderSettingsSheet(
                fontSize = fontSize,
                onFontSize = { v -> prefs.readerFontSize().set(v) },
                lineSpacing = lineSpacing,
                onLineSpacing = { v -> prefs.readerLineSpacing().set(v) },
                theme = themeMode,
                onTheme = { v -> prefs.readerTheme().set(v) },
            )
        }
    }
}

@Composable
private fun ReaderSettingsSheet(
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    lineSpacing: Float,
    onLineSpacing: (Float) -> Unit,
    theme: Int,
    onTheme: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("Font size: ${fontSize}sp", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = fontSize.toFloat(),
            onValueChange = { onFontSize(it.toInt()) },
            valueRange = 12f..24f,
            steps = 11,
        )
        Spacer(Modifier.height(8.dp))
        Text("Line spacing: ${"%.1f".format(lineSpacing)}x", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = lineSpacing,
            onValueChange = { onLineSpacing(it) },
            valueRange = 1.0f..2.5f,
            steps = 14,
        )
        Spacer(Modifier.height(12.dp))
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        listOf(0 to "Follow system", 1 to "Light", 2 to "Dark").forEach { (code, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTheme(code) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = theme == code, onClick = { onTheme(code) })
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Background / foreground pair for the reader based on theme mode + system dark setting. */
@Composable
private fun readerColors(themeMode: Int, systemDark: Boolean): Pair<Color, Color> = when (themeMode) {
    1 -> Color.White to Color.Black
    2 -> Color(0xFF101010) to Color(0xFFE0E0E0)
    else -> if (systemDark) Color(0xFF101010) to Color(0xFFE0E0E0) else Color.White to Color.Black
}
