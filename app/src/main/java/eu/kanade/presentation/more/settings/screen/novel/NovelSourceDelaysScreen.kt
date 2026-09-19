package eu.kanade.presentation.more.settings.screen.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import reikai.novel.download.NovelDownloadPacing
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

/**
 * Each installed novel source's delay between downloaded chapters. Novels only: manga extensions pace
 * their own requests through the rate limits their authors build into their clients.
 */
class NovelSourceDelaysScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<NovelSourceDelaysViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        var editing by remember { mutableStateOf<NovelSourceDelaysViewModel.SourceDelay?>(null) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_novel_source_delay),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val sources = state.sources
            when {
                sources == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                sources.isEmpty() -> EmptyScreen(
                    stringRes = MR.strings.pref_novel_source_delay_empty,
                    modifier = Modifier.padding(contentPadding),
                )
                else -> LazyColumn(contentPadding = contentPadding) {
                    items(sources, key = { it.id }) { source ->
                        SourceDelayRow(source, state.globalMs, onClick = { editing = source })
                    }
                }
            }
        }

        editing?.let { source ->
            SourceDelayDialog(
                source = source,
                globalMs = state.globalMs,
                onPick = { delayMs ->
                    viewModel.setDelay(source.id, delayMs)
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }
}

@Composable
private fun SourceDelayRow(source: NovelSourceDelaysViewModel.SourceDelay, globalMs: Long, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = source.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = source.lang,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = source.delayMs?.let { downloadDelayLabel(it) }
                ?: stringResource(MR.strings.pref_novel_source_delay_default, downloadDelayLabel(globalMs)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (source.delayMs == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun SourceDelayDialog(
    source: NovelSourceDelaysViewModel.SourceDelay,
    globalMs: Long,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Null is the source following the global delay rather than keeping one of its own.
    val options: List<Long?> = listOf(null) + NovelDownloadPacing.DELAY_OPTIONS_MS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = source.name) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) },
                    ) {
                        RadioButton(selected = option == source.delayMs, onClick = { onPick(option) })
                        Text(
                            text = option?.let { downloadDelayLabel(it) }
                                ?: stringResource(
                                    MR.strings.pref_novel_source_delay_default,
                                    downloadDelayLabel(globalMs),
                                ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(MR.strings.action_cancel)) }
        },
    )
}

/** A delay as Settings shows it, in seconds: "0.5 s", "2 s". */
@Composable
fun downloadDelayLabel(delayMs: Long): String {
    val seconds = if (delayMs % 1_000L == 0L) "${delayMs / 1_000L}" else "${delayMs / 1_000.0}"
    return stringResource(MR.strings.download_delay_seconds, seconds)
}
