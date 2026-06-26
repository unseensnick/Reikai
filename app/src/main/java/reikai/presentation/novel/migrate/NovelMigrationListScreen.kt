package reikai.presentation.novel.migrate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import eu.kanade.presentation.util.Screen
import reikai.domain.novel.model.NovelMigrationFlag
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
    val id = row.novel.id
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = row.novel.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val chosen = row.chosenTarget
        when {
            row.resolving -> RowStatus { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
            chosen != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = chosen.title,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { screenModel.toggleExpanded(id) }) {
                    Text(text = stringResource(MR.strings.action_change))
                }
                IconButton(onClick = { screenModel.clearChoice(id) }) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                }
            }
            row.suggested != null -> {
                val (source, item) = row.suggested!!
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${source.name}: ${item.name}",
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Button(onClick = { screenModel.acceptSuggested(id) }) {
                        Text(text = stringResource(MR.strings.action_accept))
                    }
                    ExpandToggle(row.expanded) { screenModel.toggleExpanded(id) }
                }
            }
            row.searching -> RowStatus {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(text = stringResource(MR.strings.action_search), modifier = Modifier.padding(start = 8.dp))
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = stringResource(MR.strings.no_results_found), modifier = Modifier.weight(1f))
                TextButton(onClick = { screenModel.toggleExpanded(id) }) {
                    Text(text = stringResource(MR.strings.action_change))
                }
            }
        }

        if (row.expanded) {
            OverrideSection(row = row, screenModel = screenModel)
        }
    }
}

@Composable
private fun RowStatus(content: @Composable () -> Unit) {
    Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) { content() }
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

@Composable
private fun OverrideSection(
    row: NovelMigrationListScreenModel.Row,
    screenModel: NovelMigrationListScreenModel,
) {
    val id = row.novel.id
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
    // Flat candidate list (bounded: one page per source), no nested LazyColumn.
    row.candidates.forEach { (source, item) ->
        Text(
            text = "${source.name}: ${item.name}",
            modifier = Modifier
                .fillMaxWidth()
                .clickable { screenModel.pick(id, source.id, item.path) }
                .padding(vertical = 10.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
