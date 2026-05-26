package yokai.presentation.novel.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.getResourceColor
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.novel.host.LnPluginHost
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager

/**
 * Compose surface rendered in the Light novel sources tab of the legacy [BrowseController]
 * (Phase 8 follow-up CR8/CR9). Lists every [NovelSource] currently registered with
 * [NovelSourceManager].
 *
 * Lifecycle: a fresh [LnPluginHost] is created here and `installer.loadInstalled(host)` is
 * called in [LaunchedEffect] so the manager populates with the user's installed plugins on
 * tab entry. Mirrors what the existing per-screen patterns do
 * ([yokai.presentation.novel.browse.NovelBrowseScreen], the debug probe screen, etc.).
 *
 * Theme: same Phase 6 F12 / Phase 8 follow-up CR5 hotfix pattern — wrap content in a
 * Surface that pins container + content color to `?attr/background` /
 * `?attr/actionBarTintColor` so text is readable regardless of the user's light / dark
 * preference vs. the legacy Browse view's surface scheme.
 *
 * Tap a source row → invokes [onOpenSource]. The caller pushes the appropriate catalog browse
 * Controller (CR10 — for now, the existing `NovelBrowseController` since the catalog refactor
 * is deferred).
 */
@Composable
fun LnSourceListContent(
    onOpenSource: (NovelSource) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val manager = remember { Injekt.get<NovelSourceManager>() }
    val installer = remember { Injekt.get<LnPluginInstaller>() }
    val networkHelper = remember { Injekt.get<NetworkHelper>() }
    val host = remember { LnPluginHost(context, networkHelper.client) }
    val sources by manager.sources.collectAsState(initial = manager.getAll())

    // Re-hydrate installed plugins into this fresh host on tab entry so the manager's
    // sources list reflects everything in NovelPreferences.installedPluginUrls(). Idempotent
    // if the manager is already populated.
    LaunchedEffect(host) { installer.loadInstalled(host) }

    val bgColor = remember(context) {
        Color(context.getResourceColor(eu.kanade.tachiyomi.R.attr.background))
    }
    val fgColor = remember(context) {
        Color(context.getResourceColor(eu.kanade.tachiyomi.R.attr.actionBarTintColor))
    }
    Surface(
        modifier = modifier.fillMaxSize(),
        color = bgColor,
        contentColor = fgColor,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            if (sources.isEmpty()) {
                EmptyState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = sources, key = { it.id }) { source ->
                        SourceRow(source = source, onClick = { onOpenSource(source) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.LibraryBooks,
                contentDescription = null,
                tint = LocalContentColor.current.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No Light novel sources installed",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Open the Extensions sheet below and install a plugin under the Light novels sub-tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun SourceRow(source: NovelSource, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.LibraryBooks,
            contentDescription = null,
            modifier = Modifier.padding(end = 12.dp),
            tint = LocalContentColor.current.copy(alpha = 0.7f),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${source.id}  •  v${source.version}",
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
        }
    }
}
