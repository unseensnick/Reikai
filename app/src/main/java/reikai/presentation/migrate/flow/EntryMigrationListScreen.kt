package reikai.presentation.migrate.flow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.util.system.toast
import reikai.domain.library.ContentType
import reikai.presentation.migrate.flow.MigratingEntryRow.CommitPhase
import reikai.presentation.migrate.flow.MigratingEntryRow.SearchPhase
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * The shared migration list: one row per entry being migrated, with the batch commit at the bottom.
 * Serves both content types through [MigrationFlowAdapter]; nothing here branches on a content type.
 */
class EntryMigrationListScreen(
    private val contentType: ContentType,
    private val entryIds: List<Long>,
    private val extraQuery: String? = null,
) : Screen(), MigrationFlowScreen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            EntryMigrationListScreenModel(contentType, entryIds, extraQuery)
        }
        val state by screenModel.state.collectAsState()
        var showTuning by rememberSaveable { mutableStateOf(false) }

        if (state.finished) {
            // Unwind the whole flow, not one step: the screen below is a stale flow step.
            LaunchedEffect(Unit) { navigator.popUntil { it !is MigrationFlowScreen } }
            return
        }

        // A deep pick is made on a screen pushed over this one, so it is collected on the way back.
        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) screenModel.collectPendingPick()
        }

        // Back is guarded all the way through the commit: rows are being mutated.
        val guardExit = !state.finished && state.rows.isNotEmpty()
        BackHandler(enabled = guardExit) { screenModel.showExitConfirm() }

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.migrationListScreenTitle),
                    subtitle = if (state.rows.size > 1) "${state.searchedCount} / ${state.rows.size}" else null,
                    navigateUp = {
                        if (guardExit) {
                            screenModel.showExitConfirm()
                        } else {
                            navigator.popUntil { it !is MigrationFlowScreen }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationFlow_acceptAllLabel),
                                    icon = Icons.Outlined.DoneAll,
                                    onClick = screenModel::acceptAll,
                                    enabled = state.hasUnaccepted,
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationFlow_searchOptionsTitle),
                                    icon = Icons.Outlined.Tune,
                                    onClick = { showTuning = true },
                                    // Matches what applyTuning itself allows, so the sheet cannot
                                    // open onto edits that would be refused.
                                    enabled = !state.isCommitting && !state.singleCommitInFlight,
                                ),
                            ),
                        )
                    },
                )
            },
            bottomBar = {
                if (state.allSearched && state.committableCount > 0 && !state.isCommitting) {
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
                            Text(text = "${stringResource(MR.strings.copy)} (${state.committableCount})")
                        }
                        Button(
                            onClick = { screenModel.showConfirm(replace = true) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = "${stringResource(MR.strings.migrate)} (${state.committableCount})")
                        }
                    }
                }
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                items(items = state.visibleRows, key = { it.entry.id.toString() }) { row ->
                    MigrationRow(row = row, screenModel = screenModel)
                }
            }
        }

        if (showTuning) {
            val context = LocalContext.current
            val busyMessage = stringResource(MR.strings.migrationFlow_busyToast)
            AdaptiveSheet(onDismissRequest = { showTuning = false }) {
                MigrationTuningSheet(
                    tuning = state.tuning,
                    supportsSmartMatch = state.supportsSmartMatch,
                    supportsChapterComparison = state.supportsChapterComparison,
                    onApply = { if (!screenModel.applyTuning(it)) context.toast(busyMessage) },
                )
            }
        }

        when (val dialog = state.dialog) {
            is EntryMigrationListScreenModel.Dialog.Confirm -> ConfirmDialog(
                dialog = dialog,
                onDismissRequest = screenModel::dismissDialog,
                onConfirm = { flags -> screenModel.commit(dialog.replace, flags) },
            )
            is EntryMigrationListScreenModel.Dialog.Progress -> ProgressDialog(
                dialog = dialog,
                onCancel = screenModel::cancelCommit,
            )
            EntryMigrationListScreenModel.Dialog.Exit -> ExitDialog(
                onDismissRequest = screenModel::dismissDialog,
                // Stopping abandons the flow, so it unwinds to wherever the flow was started,
                // the same as finishing: one pop would land on a stale flow step.
                onConfirm = { navigator.popUntil { it !is MigrationFlowScreen } },
            )
            null -> {}
        }
    }
}

@Composable
private fun MigrationRow(
    row: MigratingEntryRow,
    screenModel: EntryMigrationListScreenModel,
) {
    // Collected inside the item so one row settling recomposes that row, not the whole list.
    val search by row.search.collectAsState()
    val commit by row.commit.collectAsState()
    val chosen by row.chosen.collectAsState()
    val skipped by row.skipped.collectAsState()
    val expanded by row.expanded.collectAsState()

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (skipped) 0.5f else 1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(modifier = Modifier.width(38.dp), data = row.entry.cover)
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
                val target = chosen?.title ?: (search as? SearchPhase.Found)?.suggestion?.title
                if (target != null) {
                    Text(
                        text = target,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // An override pick can land on a different source than the suggestion; the status
                // line follows the chosen target, not the search cell.
                val chosenSourceName = chosen?.let {
                    remember(it.sourceKey) { screenModel.sourceDisplayName(it.sourceKey) }
                }
                RowStatusLine(
                    row = row,
                    search = search,
                    commit = commit,
                    skipped = skipped,
                    chosenSourceName = chosenSourceName,
                )
                RowCountLine(row = row, target = chosen ?: (search as? SearchPhase.Found)?.suggestion)
            }
            RowTrailing(
                row = row,
                search = search,
                commit = commit,
                chosen = chosen,
                skipped = skipped,
                screenModel = screenModel,
            )
        }
        if (expanded) {
            OverridePicker(row = row, screenModel = screenModel)
        }
    }
}

/** The row's open override picker: a query field over per-source result strips. */
@Composable
private fun OverridePicker(
    row: MigratingEntryRow,
    screenModel: EntryMigrationListScreenModel,
) {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val overrides by row.overrides.collectAsState()
    var query by rememberSaveable(row.entry.id.toString()) { mutableStateOf(row.entry.title) }

    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(text = stringResource(MR.strings.action_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(
                    onClick = { screenModel.searchOverrides(row.entry.id, query) },
                    enabled = query.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = stringResource(MR.strings.action_search),
                    )
                }
            },
        )

        when (val state = overrides) {
            MigratingEntryRow.OverrideState.Idle -> {}
            MigratingEntryRow.OverrideState.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            is MigratingEntryRow.OverrideState.Loaded -> state.strips.forEach { strip ->
                OverrideStripRow(
                    strip = strip,
                    onPick = { screenModel.pick(row.entry.id, it) },
                    onBrowseSource = {
                        if (!openDeepPicker(navigator, row.entry, strip.sourceKey, query)) {
                            context.toast(MR.strings.internal_error)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OverrideStripRow(
    strip: MigratingEntryRow.OverrideStrip,
    onPick: (MigrationCandidate) -> Unit,
    onBrowseSource: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = strip.sourceName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp, bottom = 2.dp),
        )
        // Browsing the whole source is the way out when the search cannot reach the title, which is
        // exactly when the strip below has nothing in it.
        IconButton(onClick = onBrowseSource) {
            Icon(
                imageVector = Icons.Outlined.TravelExplore,
                contentDescription = stringResource(MR.strings.migrationFlow_browseSource),
                modifier = Modifier.size(18.dp),
            )
        }
    }
    when {
        // A source that threw says so, rather than looking like a source with nothing to offer.
        strip.error != null -> Text(
            text = strip.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        strip.candidates.isEmpty() -> Text(
            text = stringResource(MR.strings.no_results_found),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> LazyRow {
            items(items = strip.candidates, key = { it.key }) { candidate ->
                Column(
                    modifier = Modifier
                        .width(96.dp)
                        .padding(end = 8.dp)
                        .clickable { onPick(candidate) },
                ) {
                    MangaCover.Book(modifier = Modifier.fillMaxWidth(), data = candidate.cover)
                    Text(
                        text = candidate.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** "SourceA -> SourceB", or the row's terminal state when it has one. */
@Composable
private fun RowStatusLine(
    row: MigratingEntryRow,
    search: SearchPhase,
    commit: CommitPhase,
    skipped: Boolean,
    chosenSourceName: String?,
) {
    val isError = commit is CommitPhase.Failed || search is SearchPhase.Failed
    val status = when {
        commit is CommitPhase.Failed -> stringResource(MR.strings.migrationListScreen_noMatchFoundText)
        commit is CommitPhase.Migrated -> stringResource(MR.strings.migrate)
        // Skip outranks a failed search: the escape hatch has to confirm itself visibly.
        skipped -> stringResource(MR.strings.migrationFlow_skippedChip)
        chosenSourceName != null -> chosenSourceName
        search is SearchPhase.Failed -> stringResource(MR.strings.migrationListScreen_noMatchFoundText)
        search is SearchPhase.Found -> search.sourceName
        search is SearchPhase.Searching -> stringResource(MR.strings.loading)
        search is SearchPhase.NoMatch -> stringResource(MR.strings.migrationListScreen_noMatchFoundText)
        else -> null
    }
    Text(
        text = listOfNotNull(row.entry.sourceName, status).joinToString(" → "),
        style = MaterialTheme.typography.bodySmall,
        color = when {
            isError -> MaterialTheme.colorScheme.error
            commit is CommitPhase.Migrated -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Mono latest-chapter numbers, the basis prioritize-by-chapters and hide-without-updates compare on. */
@Composable
private fun RowCountLine(row: MigratingEntryRow, target: MigrationCandidate?) {
    val current = row.entry.latestChapter
    val targetLatest = target?.latestChapter
    if (current == null && targetLatest == null) return
    val unknown = stringResource(MR.strings.migrationListScreen_unknownLatestChapter)
    Text(
        text = stringResource(
            MR.strings.migrationListScreen_latestChapterLabel,
            "${current?.let(
                ::formatChapterNumber,
            ) ?: unknown} → ${targetLatest?.let(::formatChapterNumber) ?: unknown}",
        ),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RowTrailing(
    row: MigratingEntryRow,
    search: SearchPhase,
    commit: CommitPhase,
    chosen: MigrationCandidate?,
    skipped: Boolean,
    screenModel: EntryMigrationListScreenModel,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    when {
        commit is CommitPhase.Committing -> Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        commit is CommitPhase.Failed -> TextButton(onClick = { screenModel.retry(row.entry.id) }) {
            Text(text = stringResource(MR.strings.action_retry))
        }
        commit is CommitPhase.Migrated -> Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(MR.strings.migrate),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // Accept: filled once a target is accepted, outlined while it is only a suggestion. Tapping
        // an accepted row gives the target back, which is why it stays a control and not a chip.
        !skipped && (chosen != null || search.suggestion != null) -> {
            val accepted = chosen != null
            val description = stringResource(
                if (accepted) MR.strings.migrationFlow_acceptedLabel else MR.strings.action_accept,
            )
            if (accepted) {
                FilledTonalIconButton(onClick = { screenModel.toggleAccept(row.entry.id) }) {
                    Icon(imageVector = Icons.Filled.Check, contentDescription = description)
                }
            } else {
                OutlinedIconButton(onClick = { screenModel.toggleAccept(row.entry.id) }) {
                    Icon(imageVector = Icons.Filled.Check, contentDescription = description)
                }
            }
        }
    }
    // The menu renders in every non-terminal row state: skip has to stay reachable while a row is
    // still searching, which is exactly when a hung source needs escaping.
    if (commit is CommitPhase.Migrated) return
    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(MR.strings.action_menu_overflow_description),
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            // Offered in every state, including while the row is still searching: a match the user
            // can already see is wrong should not have to wait for the search to give up.
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.migrationListScreen_searchManuallyActionLabel)) },
                onClick = {
                    menuExpanded = false
                    screenModel.toggleExpanded(row.entry.id)
                },
            )
            if (!skipped && commit !is CommitPhase.Committing) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(MR.strings.migrationListScreen_migrateNowActionLabel)) },
                    onClick = {
                        menuExpanded = false
                        screenModel.commitSingle(row.entry.id, replace = true)
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(MR.strings.migrationListScreen_copyNowActionLabel)) },
                    onClick = {
                        menuExpanded = false
                        screenModel.commitSingle(row.entry.id, replace = false)
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(
                            if (skipped) {
                                MR.strings.migrationFlow_restoreActionLabel
                            } else {
                                MR.strings.migrationListScreen_skipActionLabel
                            },
                        ),
                    )
                },
                onClick = {
                    menuExpanded = false
                    screenModel.toggleSkip(row.entry.id)
                },
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    dialog: EntryMigrationListScreenModel.Dialog.Confirm,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<MigrationDataFlag>) -> Unit,
) {
    // Keyed on the saved set because it arrives with the async scan; re-keying seeds the checkboxes
    // once the real values land instead of leaving them on the empty first frame.
    var selected by rememberSaveable(dialog.savedFlags, stateSaver = migrationFlagSaver) {
        mutableStateOf(dialog.savedFlags)
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = pluralStringResource(
                    if (dialog.replace) {
                        MR.plurals.migrationListScreen_migrateDialog_migrateTitle
                    } else {
                        MR.plurals.migrationListScreen_migrateDialog_copyTitle
                    },
                    dialog.count,
                    dialog.count,
                ),
            )
        },
        text = {
            Column {
                MigrationFlagChecks(
                    applicable = dialog.applicableFlags,
                    selected = selected,
                    onToggle = { flag ->
                        selected = if (flag in selected) selected - flag else selected + flag
                    },
                )
                Text(
                    text = stringResource(MR.strings.migrationFlow_tracksNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (dialog.untouched > 0) {
                    Text(
                        text = pluralStringResource(
                            MR.plurals.migrationListScreen_migrateDialog_skipText,
                            dialog.untouched,
                            dialog.untouched,
                        ),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.migrationListScreen_migrateDialog_cancelLabel))
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected) },
                // The scan decides which checkboxes exist, so confirming before it lands could
                // migrate with a set the user never saw.
                enabled = !dialog.loadingFlags,
            ) {
                Text(
                    text = stringResource(
                        if (dialog.replace) {
                            MR.strings.migrationListScreen_migrateDialog_migrateLabel
                        } else {
                            MR.strings.migrationListScreen_migrateDialog_copyLabel
                        },
                    ),
                )
            }
        },
    )
}

@Composable
private fun ProgressDialog(
    dialog: EntryMigrationListScreenModel.Dialog.Progress,
    onCancel: () -> Unit,
) {
    AlertDialog(
        // Deliberately not dismissable: rows are being mutated behind it.
        onDismissRequest = {},
        title = { Text(text = stringResource(MR.strings.migrationListScreenTitle)) },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = {
                        if (dialog.total == 0) 0f else dialog.done.toFloat() / dialog.total
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${dialog.done} / ${dialog.total}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(MR.strings.migrationListScreen_progressDialog_cancelLabel))
            }
        },
    )
}

@Composable
private fun ExitDialog(onDismissRequest: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.migrationListScreen_exitDialogTitle)) },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.migrationListScreen_exitDialog_cancelLabel))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(MR.strings.migrationListScreen_exitDialog_stopLabel))
            }
        },
    )
}
