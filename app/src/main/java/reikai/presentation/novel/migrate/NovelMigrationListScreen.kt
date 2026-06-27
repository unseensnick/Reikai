package reikai.presentation.novel.migrate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import reikai.data.coil.NovelCover
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.novel.host.NovelItem
import reikai.presentation.novel.browse.NovelBrowseListCell
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.globalsearch.SearchState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Unified novel migration for 1..N novels (the single-novel Migrate is this screen with one row). Each
 * row auto-searches its title across sources when it scrolls into view, shows an unchecked suggested
 * top hit to Accept or a Change override, materialises the picked target lazily, then the batch is
 * Copied / Migrated. See [NovelMigrationListScreenModel].
 */
class NovelMigrationListScreen(
    private val novelIds: List<Long>,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelMigrationListScreenModel(novelIds) }
        val state by screenModel.state.collectAsState()

        LaunchedEffect(state.migrated) { if (state.migrated) navigator.pop() }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.action_migrate),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            bottomBar = {
                if (state.chosenCount > 0) {
                    Button(
                        onClick = screenModel::showConfirm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.padding.medium),
                    ) {
                        Text(text = "${stringResource(MR.strings.action_migrate)} (${state.chosenCount})")
                    }
                }
            },
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = contentPadding,
            ) {
                items(items = state.rows, key = { it.novel.id }) { row ->
                    // First composition of a row = it scrolled into view: trigger its search once.
                    LaunchedEffect(row.novel.id) { screenModel.searchRow(row.novel.id) }
                    MigrationRow(row = row, screenModel = screenModel)
                    HorizontalDivider()
                }
            }
        }

        if (state.isMigrating) {
            LoadingScreen(modifier = Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)))
        }

        if (state.showConfirm) {
            MigrateConfirmDialog(
                novelCount = state.chosenCount,
                skipped = state.skippedCount,
                initialFlags = state.initialFlags,
                applicableFlags = state.applicableFlags,
                onDismissRequest = screenModel::dismissConfirm,
                onConfirm = screenModel::migrate,
            )
        }
    }
}

@Composable
private fun MigrationRow(
    row: NovelMigrationListScreenModel.Row,
    screenModel: NovelMigrationListScreenModel,
) {
    val navigator = LocalNavigator.currentOrThrow
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // Source -> target comparison: the novel you're moving, an arrow, and the match for it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            NovelThumb(
                url = row.novel.thumbnailUrl,
                site = row.sourceSite,
                lastModified = row.novel.coverLastModified,
                novelId = row.novel.id,
                onClick = { navigator.push(NovelScreen(row.novel.source, row.novel.url)) },
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    text = row.novel.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${row.sourceChapterCount} ch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            TargetSlot(row = row, modifier = Modifier.weight(1f))
        }
        ActionRow(row = row, screenModel = screenModel)
        if (row.expanded) {
            OverrideSection(row = row, screenModel = screenModel)
        }
    }
}

/** The target half of a row: a spinner while it resolves or searches, the chosen target (cover +
 *  count), the unchecked top suggestion (cover + source), or "no results". */
@Composable
private fun TargetSlot(
    row: NovelMigrationListScreenModel.Row,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.currentOrThrow
    val chosen = row.chosenTarget
    when {
        row.resolving -> Spinner(modifier)
        chosen != null -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            NovelThumb(
                url = chosen.thumbnailUrl,
                site = row.chosenSite,
                lastModified = chosen.coverLastModified,
                novelId = chosen.id,
                onClick = { navigator.push(NovelScreen(chosen.source, chosen.url)) },
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    text = chosen.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TargetChapterCount(sourceCount = row.sourceChapterCount, targetCount = row.targetChapterCount)
            }
        }
        row.suggested != null -> {
            val (source, item) = row.suggested!!
            Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                ResultCover(
                    item = item,
                    site = source.site,
                    onClick = { navigator.push(NovelScreen(source.id, item.path)) },
                )
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        row.searching -> Spinner(modifier)
        else -> Text(
            text = stringResource(MR.strings.no_results_found),
            modifier = modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Per-row controls under the comparison: Accept a suggestion, Change (search manually), or clear. */
@Composable
private fun ActionRow(
    row: NovelMigrationListScreenModel.Row,
    screenModel: NovelMigrationListScreenModel,
) {
    val id = row.novel.id
    val chosen = row.chosenTarget
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            row.resolving || row.searching -> Unit
            chosen != null -> {
                TextButton(onClick = { screenModel.toggleExpanded(id) }) {
                    Text(text = stringResource(MR.strings.action_change))
                }
                IconButton(onClick = { screenModel.clearChoice(id) }) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                }
            }
            row.suggested != null -> {
                Button(onClick = { screenModel.acceptSuggested(id) }) {
                    Text(text = stringResource(MR.strings.action_accept))
                }
                ExpandToggle(row.expanded) { screenModel.toggleExpanded(id) }
            }
            else -> TextButton(onClick = { screenModel.toggleExpanded(id) }) {
                Text(text = stringResource(MR.strings.action_change))
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ExpandToggle(expanded: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
        )
    }
}

/** Cover for a search-result target ([NovelItem]); tap opens its details to verify before picking. */
@Composable
private fun ResultCover(item: NovelItem, site: String?, onClick: () -> Unit) {
    MangaCover.Book(
        data = NovelCover(url = item.cover, site = site, isNovelFavorite = false, lastModified = 0L),
        modifier = Modifier.width(40.dp),
        onClick = onClick,
    )
}

/** Cover for a stored novel (the source, or a chosen target); tap opens its details. */
@Composable
private fun NovelThumb(url: String?, site: String?, lastModified: Long, novelId: Long, onClick: () -> Unit) {
    MangaCover.Book(
        data = NovelCover(url = url, site = site, isNovelFavorite = false, lastModified = lastModified, novelId = novelId),
        modifier = Modifier.width(40.dp),
        onClick = onClick,
    )
}

/** Target chapter count, with the shortfall vs the source in error colour when fewer (a migration
 *  regression). A paged source can under-count until all pages load, so the flag is conservative;
 *  the cover taps through to details to verify. */
@Composable
private fun TargetChapterCount(sourceCount: Int, targetCount: Int?) {
    if (targetCount == null) return
    val delta = targetCount - sourceCount
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$targetCount ch",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (delta < 0) {
            Text(
                text = " · ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$delta",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun OverrideSection(
    row: NovelMigrationListScreenModel.Row,
    screenModel: NovelMigrationListScreenModel,
) {
    val id = row.novel.id
    val navigator = LocalNavigator.currentOrThrow
    var query by remember(id) { mutableStateOf(row.novel.title) }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(text = stringResource(MR.strings.action_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        trailingIcon = {
            IconButton(onClick = { screenModel.research(id, query) }) {
                Icon(imageVector = Icons.Filled.Check, contentDescription = null)
            }
        },
    )
    // Browse-style picker (bounded: one page per source), grouped under each source's name. Tap a row
    // to pick it as the target; long-press opens its details to verify first.
    row.results.forEach { result ->
        val novels = (result.state as? SearchState.Success)?.novels.orEmpty()
        if (novels.isNotEmpty()) {
            Text(
                text = result.source.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            novels.forEach { item ->
                NovelBrowseListCell(
                    item = item,
                    inLibrary = false,
                    site = result.source.site,
                    onClick = { screenModel.pick(id, result.source.id, item.path) },
                    onLongClick = { navigator.push(NovelScreen(result.source.id, item.path)) },
                )
            }
        }
    }
    if (row.candidates.isEmpty() && !row.searching) {
        Text(
            text = stringResource(MR.strings.no_results_found),
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MigrateConfirmDialog(
    novelCount: Int,
    skipped: Int,
    initialFlags: Set<NovelMigrationFlag>,
    applicableFlags: Set<NovelMigrationFlag>,
    onDismissRequest: () -> Unit,
    onConfirm: (flags: Set<NovelMigrationFlag>, replace: Boolean) -> Unit,
) {
    // selected keeps the full saved set; only applicable flags are shown, so a hidden flag's saved
    // state is preserved (and the use case no-ops it per-novel).
    var selected by remember { mutableStateOf(initialFlags) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            val base = "${stringResource(MR.strings.action_migrate)} ($novelCount)"
            Text(text = if (skipped > 0) "$base  •  $skipped skipped" else base)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
                applicableFlags.forEach { flag ->
                    LabeledCheckbox(
                        label = stringResource(flag.titleRes),
                        checked = flag in selected,
                        onCheckedChange = { checked ->
                            selected = selected.toMutableSet().apply { if (checked) add(flag) else remove(flag) }
                        },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
                TextButton(onClick = { onConfirm(selected, false) }) { Text(text = stringResource(MR.strings.copy)) }
                TextButton(onClick = { onConfirm(selected, true) }) { Text(text = stringResource(MR.strings.migrate)) }
            }
        },
    )
}
