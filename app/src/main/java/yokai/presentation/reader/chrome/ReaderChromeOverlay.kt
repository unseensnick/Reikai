package yokai.presentation.reader.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import yokai.presentation.component.ReikaiTopBar

/**
 * Phase 2.1 (Option F1): the manga reader's top chrome, rendered in a Compose overlay hosted by the
 * legacy `ReaderActivity` (via a `compose_overlay` ComposeView over the viewer). A dumb renderer over
 * [ReaderChromeState]: the Activity still owns all behavior, window flags, and the bottom chaptersSheet
 * chrome (which lands in Compose in Phase 2.2). The full-size [Box] leaves its empty area free of
 * pointer input so touches pass through to the viewer below.
 */
@Composable
fun ReaderChromeOverlay(
    state: ReaderChromeState,
    onBack: () -> Unit,
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
    }
}

/** Inbound state for the Compose reader chrome. Updated by `ReaderActivity` at its existing chrome
 *  update points (menu toggle, manga load, chapter change). */
data class ReaderChromeState(
    val menuVisible: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
)
