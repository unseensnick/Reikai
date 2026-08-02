package reikai.presentation.migrate.flow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.util.system.toast
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.browse.EntryBrowseGridCell
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

private val PICKER_CELL_WIDTH = 112.dp

/**
 * The unified migration list, one screen for both content types over
 * [EntryMigrationListScreenModel]. Compact rows expand into the compare view plus the inline
 * override picker; the verb is chosen once in the bottom bar; the confirm dialog carries the flags.
 */
class EntryMigrationListScreen(
    private val contentType: ContentType,
    private val entryIds: List<Long>,
    private val extraQuery: String? = null,
) : Screen() {

    /** Deep-picker hand-back: (current raw id, stored target raw id). Ids only, so the field stays
     *  serializable; consumed by a LaunchedEffect when this screen re-enters composition. */
    private var matchOverride: Pair<Long, Long>? = null

    fun addMatchOverride(currentRawId: Long, targetRawId: Long) {
        matchOverride = currentRawId to targetRawId
    }

    /** Whether this list is migrating the given entry: the deep pickers hand their pick back to the
     *  closest list that OWNS the entry, never to an unrelated outer flow left on the stack. */
    fun owns(type: ContentType, rawId: Long): Boolean = type == contentType && rawId in entryIds

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel {
            EntryMigrationListScreenModel(contentType, entryIds, extraQuery)
        }
        val state by screenModel.state.collectAsState()

        var showExitDialog by rememberSaveable { mutableStateOf(false) }
        var showTuningSheet by rememberSaveable { mutableStateOf(false) }

        val doneMessage = pluralStringResource(
            MR.plurals.migrationFlow_doneToast,
            state.finishedCount,
            state.finishedCount,
        )
        LaunchedEffect(state.finished) {
            if (state.finished) {
                if (state.finishedCount > 0) context.toast(doneMessage)
                navigator.pop()
            }
        }

        LaunchedEffect(matchOverride) {
            val (current, target) = matchOverride ?: return@LaunchedEffect
            screenModel.overrideWithStored(current, target)
            matchOverride = null
        }

        LaunchedEffect(screenModel) {
            screenModel.events.collect {
                when (it) {
                    EntryMigrationListScreenModel.Event.PickFailed ->
                        context.toast(MR.strings.migrationListScreen_matchWithoutChapterToast)
                }
            }
        }

        val guardExit = !state.finished && state.rows.isNotEmpty()
        BackHandler(enabled = guardExit) { showExitDialog = true }

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.action_migrate),
                    // Mono-adjacent "n / total" searched, per the design note; numbers only, so the
                    // raw format needs no translation.
                    subtitle = if (state.rows.size > 1) {
                        "${state.searchedCount} / ${state.rows.size}"
                    } else {
                        null
                    },
                    navigateUp = { if (guardExit) showExitDialog = true else navigator.pop() },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationFlow_acceptAllLabel),
                                    icon = Icons.Outlined.DoneAll,
                                    onClick = screenModel::acceptAllSuggestions,
                                    enabled = state.hasUnacceptedSuggestions,
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationFlow_searchOptionsTitle),
                                    icon = Icons.Outlined.Tune,
                                    onClick = { showTuningSheet = true },
                                    // Applying tuning resets rows a running commit is reading.
                                    enabled = !state.isMigrating && !state.hasActiveSingleCommit,
                                ),
                            ),
                        )
                    },
                )
            },
            bottomBar = {
                // Commit only once every row has searched (the design's all-searched gate; skipped
                // rows are exempt, the escape hatch for a hung source) and no single-row commit or
                // pick resolve is in flight (the batch would double-commit or snapshot a stale target).
                if (state.chosenCount > 0 && state.allSearched && !state.isMigrating &&
                    !state.hasActiveSingleCommit && !state.hasActiveResolve
                ) {
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
            if (state.visibleRows.isEmpty()) {
                // Mid-search a hide toggle can transiently empty a short list: keep the loading
                // surface until the batch settles, and only then call it empty.
                if (state.rows.isNotEmpty() && !state.allSearched) {
                    LoadingScreen(modifier = Modifier.padding(contentPadding))
                } else {
                    EmptyScreen(
                        stringRes = MR.strings.no_results_found,
                        modifier = Modifier.padding(contentPadding),
                    )
                }
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = contentPadding,
            ) {
                items(items = state.visibleRows, key = { it.entry.id.toString() }) { row ->
                    MigrationRow(row = row, screenModel = screenModel)
                    HorizontalDivider()
                }
            }
        }

        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text(text = stringResource(MR.strings.migrationListScreen_exitDialogTitle)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            navigator.pop()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.migrationListScreen_exitDialog_stopLabel))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text(text = stringResource(MR.strings.migrationListScreen_exitDialog_cancelLabel))
                    }
                },
            )
        }

        if (showTuningSheet) {
            MigrationTuningSheet(
                tuning = state.tuning,
                supportsSmartMatch = state.supportsSmartMatch,
                supportsChapterComparison = state.supportsChapterComparison,
                onDismissRequest = { showTuningSheet = false },
                onApply = {
                    showTuningSheet = false
                    screenModel.applyTuning(it)
                },
            )
        }

        if (state.showConfirm) {
            MigrationConfirmDialog(
                count = state.chosenCount,
                skipped = state.skippedCount,
                replace = state.confirmReplace,
                flagsLoading = state.confirmFlagsLoading,
                initialFlags = state.initialFlags,
                applicableFlags = state.applicableFlags,
                onDismissRequest = screenModel::dismissConfirm,
                onConfirm = { flags -> screenModel.commit(flags, state.confirmReplace) },
            )
        }

        if (state.isMigrating) {
            MigrationProgressDialog(
                done = state.progressDone,
                total = state.progressTotal,
                replace = state.lastReplace,
                onCancel = screenModel::cancelCommit,
            )
        }
    }
}

@Composable
private fun MigrationRow(
    row: EntryMigrationListScreenModel.Row,
    screenModel: EntryMigrationListScreenModel,
) {
    val navigator = LocalNavigator.currentOrThrow
    val id = row.entry.id
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Skipped rows dim but stay in place, per the design's restorable-skip.
            .alpha(if (row.skipped) 0.5f else 1f),
    ) {
        // Only the compact header toggles expansion: a clickable spanning the expanded body made
        // every stray tap in it (strip gutters, texts) collapse the row mid-interaction.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { screenModel.toggleExpanded(id) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            MangaCover.Book(
                modifier = Modifier.width(38.dp),
                data = row.entry.cover,
                onClick = { row.entry.openDetails(navigator) },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    text = row.entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RowMetaLine(row)
                RowCountLine(row)
            }
            RowTrailing(row = row, screenModel = screenModel)
        }
        if (row.expanded) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                ExpandedSection(row = row, screenModel = screenModel)
            }
        }
    }
}

/** "CurrentSource -> TargetSource" (or the choose-a-target hint / skipped chip / failure line). */
@Composable
private fun RowMetaLine(row: EntryMigrationListScreenModel.Row) {
    val target = when {
        row.failed -> stringResource(MR.strings.migrationFlow_rowFailed)
        row.skipped -> stringResource(MR.strings.migrationFlow_skippedChip)
        row.chosen != null -> row.chosenSourceName
        row.suggested != null -> row.suggestedSourceName
        else -> stringResource(MR.strings.migrationFlow_chooseTarget)
    }
    Text(
        text = listOfNotNull(row.entry.sourceName, target).joinToString(" → "),
        style = MaterialTheme.typography.bodySmall,
        color = if (row.failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Mono chapter counts with the shortfall delta in the error color (paired with its minus sign). */
@Composable
private fun RowCountLine(row: EntryMigrationListScreenModel.Row) {
    val current = row.entry.chapterCount ?: return
    val target = (row.chosen ?: row.suggested)?.chapterCount
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (target != null) "$current → $target" else "$current",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (target != null && target < current) {
            Text(
                text = " ${target - current}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RowTrailing(
    row: EntryMigrationListScreenModel.Row,
    screenModel: EntryMigrationListScreenModel,
) {
    val id = row.entry.id
    var menuExpanded by remember { mutableStateOf(false) }
    when {
        row.searching || row.resolving -> Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        row.failed -> TextButton(onClick = { screenModel.retryRow(id) }) {
            Text(text = stringResource(MR.strings.action_retry))
        }
        else -> {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (!row.skipped && (row.suggested != null || row.chosen != null)) {
                    if (row.chosen != null) {
                        FilledTonalIconButton(
                            onClick = { screenModel.toggleAccept(id) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(MR.strings.action_accept),
                            )
                        }
                    } else {
                        OutlinedIconButton(onClick = { screenModel.toggleAccept(id) }) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(MR.strings.action_accept),
                            )
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(MR.strings.action_menu_overflow_description),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(text = stringResource(MR.strings.migrationListScreen_searchManuallyActionLabel))
                        },
                        onClick = {
                            menuExpanded = false
                            if (!row.expanded) screenModel.toggleExpanded(id)
                        },
                    )
                    if (!row.skipped && !row.migratedOk && (row.chosen ?: row.suggested) != null) {
                        DropdownMenuItem(
                            text = {
                                Text(text = stringResource(MR.strings.migrationListScreen_migrateNowActionLabel))
                            },
                            onClick = {
                                menuExpanded = false
                                screenModel.commitSingle(id, replace = true)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(MR.strings.migrationListScreen_copyNowActionLabel)) },
                            onClick = {
                                menuExpanded = false
                                screenModel.commitSingle(id, replace = false)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    if (row.skipped) {
                                        MR.strings.migrationFlow_restoreActionLabel
                                    } else {
                                        MR.strings.migrationListScreen_skipActionLabel
                                    },
                                ),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            screenModel.toggleSkip(id)
                        },
                    )
                }
            }
        }
    }
}

/** The expanded body: side-by-side compare, the inline override picker, and its per-source strips. */
@Composable
private fun ExpandedSection(
    row: EntryMigrationListScreenModel.Row,
    screenModel: EntryMigrationListScreenModel,
) {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val id = row.entry.id
    var query by rememberSaveable(id.toString()) { mutableStateOf(row.entry.title) }

    LaunchedEffect(id) {
        if (row.overrideStrips.isEmpty() && !row.overrideLoading) {
            screenModel.research(id, row.entry.title)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.width(76.dp),
            data = row.entry.cover,
            onClick = { row.entry.openDetails(navigator) },
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val target = row.chosen ?: row.suggested
        val targetUi = target?.toBrowseUi()
        if (targetUi != null) {
            MangaCover.Book(
                modifier = Modifier.width(76.dp),
                data = targetUi.cover,
                onClick = { target.openDetails(navigator) },
            )
        } else {
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .height(107.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                    ),
            )
        }
    }

    // Latest chapter numbers, the basis prioritize-by-chapters and hide-without-updates compare on
    // (row counts lie across sources that split/bundle chapters), so the user can check the pick.
    val currentLatest = row.entry.latestChapter
    val targetLatest = (row.chosen ?: row.suggested)?.latestChapter
    if (currentLatest != null || targetLatest != null) {
        val unknown = stringResource(MR.strings.migrationListScreen_unknownLatestChapter)
        Text(
            text = stringResource(
                MR.strings.migrationListScreen_latestChapterLabel,
                "${currentLatest?.let(::formatChapterNumber) ?: unknown} → " +
                    (targetLatest?.let(::formatChapterNumber) ?: unknown),
            ),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(text = stringResource(MR.strings.action_search)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        trailingIcon = {
            IconButton(
                onClick = { screenModel.research(id, query) },
                enabled = query.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(MR.strings.action_search),
                )
            }
        },
    )

    if (row.overrideLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
    }
    row.overrideStrips.forEach { strip ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strip.sourceName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp, bottom = 2.dp),
            )
            IconButton(
                onClick = {
                    if (!openDeepPicker(navigator, row.entry, strip.sourceKey, query)) {
                        context.toast(MR.strings.internal_error)
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.TravelExplore,
                    contentDescription = stringResource(MR.strings.migrationFlow_browseSource),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (strip.error != null) {
            Text(
                text = strip.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (strip.candidates.isEmpty()) {
            Text(
                text = stringResource(MR.strings.no_results_found),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow {
            items(items = strip.candidates, key = { it.stableKey }) { candidate ->
                Box(
                    modifier = Modifier
                        .width(PICKER_CELL_WIDTH)
                        .padding(horizontal = 4.dp),
                ) {
                    EntryBrowseGridCell(
                        ui = candidate.toBrowseUi(),
                        displayMode = LibraryDisplayMode.ComfortableGrid,
                        onClick = { screenModel.pick(id, candidate, strip.sourceName) },
                        onLongClick = { candidate.openDetails(navigator) },
                    )
                }
            }
        }
    }
    if (row.overrideStrips.isEmpty() && !row.overrideLoading) {
        Text(
            text = stringResource(MR.strings.no_results_found),
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MigrationConfirmDialog(
    count: Int,
    skipped: Int,
    replace: Boolean,
    flagsLoading: Boolean,
    initialFlags: Set<MigrationDataFlag>,
    applicableFlags: Set<MigrationDataFlag>,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<MigrationDataFlag>) -> Unit,
) {
    // Seeded with the FULL saved set: only applicable flags render, so a hidden flag's saved state
    // survives the confirm instead of being erased from the pref for future migrations. Keyed on
    // initialFlags because the saved set arrives async (after the applicable-flag scan).
    var selected by rememberSaveable(initialFlags, stateSaver = migrationFlagSaver) {
        mutableStateOf(initialFlags)
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = pluralStringResource(
                    if (replace) {
                        MR.plurals.migrationListScreen_migrateDialog_migrateTitle
                    } else {
                        MR.plurals.migrationListScreen_migrateDialog_copyTitle
                    },
                    count,
                    count,
                ),
            )
        },
        text = {
            Column {
                if (skipped > 0) {
                    Text(
                        text = pluralStringResource(
                            MR.plurals.migrationListScreen_migrateDialog_skipText,
                            skipped,
                            skipped,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (flagsLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                } else {
                    applicableFlags.forEach { flag ->
                        LabeledCheckbox(
                            label = stringResource(flag.titleRes()),
                            checked = flag in selected,
                            onCheckedChange = {
                                selected = if (it) selected + flag else selected - flag
                            },
                        )
                    }
                }
                Text(
                    text = stringResource(MR.strings.migrationFlow_tracksNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = !flagsLoading) {
                Text(text = stringResource(if (replace) MR.strings.migrate else MR.strings.copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.migrationListScreen_migrateDialog_cancelLabel))
            }
        },
    )
}

@Composable
private fun MigrationProgressDialog(
    done: Int,
    total: Int,
    replace: Boolean,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(text = stringResource(if (replace) MR.strings.migrate else MR.strings.copy))
        },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "$done / $total",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(MR.strings.migrationListScreen_progressDialog_cancelLabel))
            }
        },
    )
}

/** Bundle-safe saver for the confirm dialog's flag selection (enum sets aren't saveable directly). */
private val migrationFlagSaver = listSaver<Set<MigrationDataFlag>, String>(
    save = { it.map(MigrationDataFlag::name) },
    restore = { it.map(MigrationDataFlag::valueOf).toSet() },
)

internal fun MigrationDataFlag.titleRes() = when (this) {
    MigrationDataFlag.CHAPTER -> MR.strings.chapters
    MigrationDataFlag.CATEGORY -> MR.strings.categories
    MigrationDataFlag.CUSTOM_COVER -> MR.strings.custom_cover
    MigrationDataFlag.NOTES -> MR.strings.action_notes
    MigrationDataFlag.REMOVE_DOWNLOAD -> MR.strings.migrationConfigScreen_removeDownloadsTitle
}
