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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import reikai.presentation.browse.EntryBrowseGridCell
import reikai.presentation.browse.toEntryBrowseUi
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.globalsearch.SearchState
import tachiyomi.domain.library.model.LibraryDisplayMode
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
                val total = state.rows.size
                val matched = state.rows.count { it.chosenTarget != null || it.suggested != null }
                AppBar(
                    title = stringResource(MR.strings.action_migrate),
                    subtitle = if (total > 1) "$total novels · $matched matched" else null,
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            bottomBar = {
                if (state.chosenCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.padding.medium),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        OutlinedButton(
                            onClick = { screenModel.showConfirm(replace = false) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = "${stringResource(MR.strings.copy)} (${state.chosenCount})")
                        }
                        Button(
                            onClick = { screenModel.showConfirm(replace = true) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = "${stringResource(MR.strings.migrate)} (${state.chosenCount})")
                        }
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
                replace = state.confirmReplace,
                initialFlags = state.initialFlags,
                applicableFlags = state.applicableFlags,
                onDismissRequest = screenModel::dismissConfirm,
                onConfirm = { flags -> screenModel.migrate(flags, state.confirmReplace) },
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
                    text = listOfNotNull(row.sourceSourceName, "${row.sourceChapterCount} ch").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            TargetSlot(row = row, modifier = Modifier.weight(1f))
            RowActions(row = row, screenModel = screenModel)
        }
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
                ChosenMeta(
                    sourceName = row.chosenSourceName,
                    sourceCount = row.sourceChapterCount,
                    targetCount = row.targetChapterCount,
                )
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

/** Trailing actions for a row, manga-style: a one-tap Accept for a suggestion, plus an overflow menu
 *  (Search manually, and Skip once a target is chosen). Hidden while a row is still fetching. */
@Composable
private fun RowActions(
    row: NovelMigrationListScreenModel.Row,
    screenModel: NovelMigrationListScreenModel,
) {
    val id = row.novel.id
    val chosen = row.chosenTarget
    val showActions = !row.resolving && !row.searching
    var menuExpanded by remember { mutableStateOf(false) }
    // Fixed-width Accept slot (empty unless a suggestion awaits acceptance) so the overflow icon and the
    // target text stay aligned across every row, accepted or not.
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (showActions && chosen == null && row.suggested != null) {
            FilledTonalIconButton(onClick = { screenModel.acceptSuggested(id) }) {
                Icon(imageVector = Icons.Filled.Check, contentDescription = stringResource(MR.strings.action_accept))
            }
        }
    }
    Box(modifier = Modifier.size(48.dp)) {
        if (showActions) {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(MR.strings.migrationListScreen_searchManuallyActionLabel)) },
                    onClick = {
                        menuExpanded = false
                        screenModel.toggleExpanded(id)
                    },
                )
                if (chosen != null) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.migrationListScreen_skipActionLabel)) },
                        onClick = {
                            menuExpanded = false
                            screenModel.clearChoice(id)
                        },
                    )
                }
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

/** Shared cover width across the migration row, so source and target thumbnails match. */
private val MIGRATION_COVER_WIDTH = 48.dp

/** Cover-cell width in the override picker grid, matching the novel global-search results row. */
private val PICKER_CELL_WIDTH = 112.dp

/** Cover for a search-result target ([NovelItem]); tap opens its details to verify before picking. */
@Composable
private fun ResultCover(item: NovelItem, site: String?, onClick: () -> Unit) {
    MangaCover.Book(
        data = NovelCover(url = item.cover, site = site, isNovelFavorite = false, lastModified = 0L),
        modifier = Modifier.width(MIGRATION_COVER_WIDTH),
        onClick = onClick,
    )
}

/** Cover for a stored novel (the source, or a chosen target); tap opens its details. */
@Composable
private fun NovelThumb(url: String?, site: String?, lastModified: Long, novelId: Long, onClick: () -> Unit) {
    MangaCover.Book(
        data = NovelCover(
            url = url,
            site = site,
            isNovelFavorite = false,
            lastModified = lastModified,
            novelId = novelId,
        ),
        modifier = Modifier.width(MIGRATION_COVER_WIDTH),
        onClick = onClick,
    )
}

/** Secondary line for the chosen target: its source name and chapter count, with the shortfall vs the
 *  source in error colour when fewer (a migration regression). Conservative for paged sources; the
 *  cover taps through to details to verify. */
@Composable
private fun ChosenMeta(sourceName: String?, sourceCount: Int, targetCount: Int?) {
    val prefix = listOfNotNull(sourceName, targetCount?.let { "$it ch" }).joinToString(" · ")
    val delta = targetCount?.minus(sourceCount)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = prefix,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (delta != null && delta < 0) {
            Text(
                text = " · $delta",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
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
            IconButton(
                onClick = { screenModel.research(id, query) },
                enabled = query.isNotBlank(),
            ) {
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
            LazyRow {
                items(items = novels, key = { it.path }) { item ->
                    Box(modifier = Modifier.width(PICKER_CELL_WIDTH).padding(horizontal = 4.dp)) {
                        EntryBrowseGridCell(
                            ui = item.toEntryBrowseUi(inLibrary = false, site = result.source.site),
                            displayMode = LibraryDisplayMode.ComfortableGrid,
                            onClick = { screenModel.pick(id, result.source.id, item.path) },
                            onLongClick = { navigator.push(NovelScreen(result.source.id, item.path)) },
                        )
                    }
                }
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
    replace: Boolean,
    initialFlags: Set<NovelMigrationFlag>,
    applicableFlags: Set<NovelMigrationFlag>,
    onDismissRequest: () -> Unit,
    onConfirm: (flags: Set<NovelMigrationFlag>) -> Unit,
) {
    // selected keeps the full saved set; only applicable flags are shown, so a hidden flag's saved
    // state is preserved (and the use case no-ops it per-novel).
    var selected by remember { mutableStateOf(initialFlags) }
    // Copy vs Migrate is already chosen on the bottom bar; the dialog only confirms what to carry over.
    val actionLabel = stringResource(if (replace) MR.strings.migrate else MR.strings.copy)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            val base = "$actionLabel ($novelCount)"
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
            TextButton(onClick = { onConfirm(selected) }) { Text(text = actionLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(text = stringResource(MR.strings.action_cancel)) }
        },
    )
}
