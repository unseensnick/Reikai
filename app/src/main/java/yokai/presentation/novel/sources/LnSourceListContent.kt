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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlin.math.roundToInt
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.getResourceColor
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager

/** Fallback language tag for sources whose lang is empty (legacy installs before bootstrap.js
 *  started passing plugin.lang through). Rendered as-is in the section header. */
private const val UNKNOWN_LANG = "?"

/**
 * Compose surface rendered in the Light novel sources tab of the legacy [BrowseController]
 * (Phase 8 follow-up CR8/CR9). Lists every [NovelSource] currently registered with
 * [NovelSourceManager].
 *
 * Lifecycle: `installer.ensureLoaded()` is called in [LaunchedEffect] so the app-scoped host
 * populates the manager with the user's installed plugins on tab entry (idempotent across screens).
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
    /**
     * Per-event scroll delta (dy in RV semantics: positive when content scrolls down, i.e. user
     * dragged finger up) plus an `atTop` flag (true when the list is at offset 0). The legacy
     * [BrowseController] forwards this to the activity app bar Y translation + bottom-nav
     * translation so the LN list participates in the same collapse-on-scroll behavior the manga
     * RV gets via `scrollViewWith`.
     */
    onScrollDelta: ((dy: Int, atTop: Boolean) -> Unit)? = null,
    /**
     * Fired when scrolling stops (user lifts finger and fling settles), with the `atTop` flag.
     * [BrowseController] uses this to snap the app bar / bottom nav to the nearest edge,
     * matching the manga side's `onScrollIdle` in `scrollViewWith`.
     */
    onScrollIdle: ((atTop: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val manager = remember { Injekt.get<NovelSourceManager>() }
    val installer = remember { Injekt.get<LnPluginInstaller>() }
    val novelPrefs = remember { Injekt.get<yokai.domain.novel.NovelPreferences>() }
    val sources by manager.sources.collectAsState(initial = manager.getAll())
    val lastUsedId by novelPrefs.lastUsedNovelSource().changes()
        .collectAsState(initial = novelPrefs.lastUsedNovelSource().get())

    // Ensure installed plugins are loaded into the app-scoped host so the manager's sources list
    // reflects everything in NovelPreferences.installedPluginUrls(). Idempotent across screens.
    LaunchedEffect(Unit) { installer.ensureLoaded() }

    val listState = rememberLazyListState()
    // Bridge Compose nested-scroll into the activity app bar's collapse mechanism. `consumed.y`
    // is negative when the user dragged finger up (revealing items below). The RV equivalent
    // dy is positive in that case, so dy = -consumed.y. atTop is read fresh per scroll event
    // so BrowseController can snap the bar back to y=0 when the list reaches the top.
    val nestedScroll = remember(onScrollDelta, listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val cb = onScrollDelta ?: return Offset.Zero
                val dy = (-consumed.y).roundToInt()
                val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                cb(dy, atTop)
                return Offset.Zero
            }
        }
    }
    // Emit a single idle event each time scrolling stops (user lifts finger AND any fling
    // settles). drop(1) skips the initial "not scrolling" emission so we don't fire on mount.
    LaunchedEffect(listState, onScrollIdle) {
        val cb = onScrollIdle ?: return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .collect { inProgress ->
                if (!inProgress) {
                    val atTop = listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0
                    cb(atTop)
                }
            }
    }

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
        Column(modifier = Modifier.fillMaxSize()) {
            if (sources.isEmpty()) {
                // Empty state still applies the inset as ordinary padding so it doesn't sit
                // under the appBar / over the bottom nav.
                EmptyState(modifier = Modifier.fillMaxSize().padding(contentPadding))
            } else {
                // Partition into Last Used (single source) + Available-by-language. Pinned
                // section is intentionally omitted today: it requires a pin row-action which
                // is its own future polish item; without that affordance there's no way to
                // populate the bucket so the header would never render.
                val lastUsed = sources.firstOrNull { it.id == lastUsedId && lastUsedId.isNotEmpty() }
                val byLang = sources.filter { it != lastUsed }
                    .groupBy { it.lang.ifBlank { UNKNOWN_LANG } }
                    .toSortedMap()
                    .mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nestedScroll),
                    state = listState,
                    // contentPadding goes on the LazyColumn (not the parent Column) so items
                    // can scroll into the padded area visually as the appBar collapses. This
                    // is the Compose equivalent of clipToPadding=false on the manga RV.
                    contentPadding = contentPadding,
                ) {
                    if (lastUsed != null) {
                        item(key = "section:last-used") { SourcesSectionHeader(text = "Last used") }
                        item(key = "last-used:${lastUsed.id}") {
                            SourceRow(source = lastUsed, onClick = { onOpenSource(lastUsed) })
                            HorizontalDivider()
                        }
                    }
                    byLang.forEach { (lang, group) ->
                        item(key = "section:lang:$lang") {
                            SourcesSectionHeader(
                                text = if (lang == UNKNOWN_LANG) lang
                                else LocaleHelper.getLocalizedDisplayName(lang),
                            )
                        }
                        items(items = group, key = { it.id }) { source ->
                            SourceRow(source = source, onClick = { onOpenSource(source) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** Section header for the LN sources LazyColumn. Matches manga's `source_header_item.xml`:
 *  bodySmall (12sp), 12dp horizontal + 8dp vertical padding. Don't confuse with the Extensions
 *  sheet's header style (16sp); the two surfaces have different specs. */
@Composable
private fun SourcesSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
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
    // Match the manga source row (source_item.xml): row container at
    // material_component_lists_two_line_height, which Reikai overrides to 56dp in
    // res/values/dimens.xml (not the Material default 72dp). Icon is 0dp x 0dp with
    // marginStart=6dp + padding=8dp constrained 1:1 = 40dp visible. Title in colorOnBackground
    // at default body size with maxLines=1. No subtitle on manga rows; language lives in the
    // section header.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = source.iconUrl,
            contentDescription = null,
            // size(56).padding(8) = 56dp layout slot, 40dp visible image, matching the manga
            // icon's row-height-constrained 1:1 with 8dp internal padding.
            modifier = Modifier.size(56.dp).padding(8.dp),
            contentScale = ContentScale.Fit,
            fallback = rememberVectorPainter(Icons.Outlined.LibraryBooks),
            error = rememberVectorPainter(Icons.Outlined.LibraryBooks),
        )
        Text(
            text = source.name,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
    }
}
