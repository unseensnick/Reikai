package yokai.presentation.reader.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import kotlin.math.roundToInt
import yokai.presentation.component.ReikaiTopBar

/**
 * Phase 2.2 (Option F1): the manga reader's chrome (top + bottom bars), rendered in a Compose overlay
 * hosted by the legacy `ReaderActivity` over the viewer. A dumb renderer over [ReaderChromeState]; the
 * Activity owns behavior, window flags, and (still) the chapter-list + quick-control buttons, which the
 * bottom bar's settings entry reaches via the legacy settings sheet until Phase 2.3. The full-size [Box]
 * keeps its empty middle free of pointer input so touches pass through to the viewer below.
 */
@Composable
fun ReaderChromeOverlay(
    state: ReaderChromeState,
    onBack: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeek: (Int) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.menuVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
        ) {
            ReikaiTopBar(
                title = {
                    Column {
                        Text(
                            text = state.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.subtitle.isNotEmpty()) {
                            Text(
                                text = state.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = state.menuVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            BottomAppBar(windowInsets = WindowInsets.navigationBars) {
                IconButton(onClick = onPrevChapter, enabled = state.hasPrevChapter) {
                    Icon(painterResource(R.drawable.ic_skip_previous_24), contentDescription = "Previous chapter")
                }
                Text(
                    text = state.currentPageText,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )

                if (state.pageCount > 1) {
                    PageSeekbar(
                        currentPage = state.currentPage,
                        pageCount = state.pageCount,
                        onSeek = onSeek,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Text(
                    text = if (state.pageCount > 0) state.pageCount.toString() else "",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                IconButton(onClick = onNextChapter, enabled = state.hasNextChapter) {
                    Icon(painterResource(R.drawable.ic_skip_next_24), contentDescription = "Next chapter")
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Reader settings")
                }
            }
        }
    }
}

/**
 * Page scrubber. Seeks live while dragging (matches the legacy seekbar), and syncs to the viewer's
 * reported page when the user isn't dragging.
 */
@Composable
private fun PageSeekbar(
    currentPage: Int,
    pageCount: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(currentPage.toFloat()) }
    LaunchedEffect(currentPage) {
        if (!dragging) sliderValue = currentPage.toFloat()
    }
    val max = (pageCount - 1).toFloat()
    Slider(
        value = sliderValue.coerceIn(0f, max),
        onValueChange = {
            dragging = true
            if (it.roundToInt() != sliderValue.roundToInt()) onSeek(it.roundToInt())
            sliderValue = it
        },
        onValueChangeFinished = { dragging = false },
        valueRange = 0f..max,
        steps = (pageCount - 2).coerceAtLeast(0),
        modifier = modifier,
    )
}

/** Inbound state for the Compose reader chrome. Updated by `ReaderActivity` at its existing chrome
 *  update points (menu toggle, manga load, chapter change, page change). */
data class ReaderChromeState(
    val menuVisible: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val currentPage: Int = 0,
    val pageCount: Int = 0,
    val currentPageText: String = "",
    val hasPrevChapter: Boolean = false,
    val hasNextChapter: Boolean = false,
)
