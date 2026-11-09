package reikai.presentation.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.collections.immutable.persistentListOf
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.Folder
import reikai.data.local.ImportGroup
import reikai.data.local.ImportProblem
import reikai.data.local.LocalImport
import reikai.domain.library.ContentType
import reikai.domain.library.labelRes
import reikai.novel.local.LocalNovelSource
import reikai.presentation.components.NameDialog
import reikai.presentation.components.readableColumn
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Picks EPUB books and comic archives, shows them as the series they will become, and copies them into the
 * local sources. [initialUris] are files another app handed over, opened with or shared to Reikai.
 */
class LocalImportScreen(private val initialUris: List<String> = emptyList()) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<LocalImportViewModel, LocalImportViewModel.Factory> {
            create(initialUris)
        }
        val state by viewModel.state.collectAsState()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.add(uris)
        }
        val pick = { picker.launch(LocalImport.MIME_TYPES) }
        var renaming by remember { mutableStateOf<ImportGroup?>(null) }

        // A single series opens once imported, since reading it is why the user came; several land in the library.
        // Not while files shared in during the import wait, which leaving would drop.
        LaunchedEffect(state.result) {
            val only = state.result?.series?.singleOrNull() ?: return@LaunchedEffect
            if (state.result?.problems?.isNotEmpty() == true || only.entryId == null) return@LaunchedEffect
            if (state.groups.isNotEmpty()) return@LaunchedEffect
            navigator.replace(
                if (only.type == ContentType.NOVELS) {
                    NovelScreen(LocalNovelSource.ID, only.path)
                } else {
                    MangaScreen(only.entryId)
                },
            )
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.local_import_title),
                    navigateUp = navigator::pop,
                    actions = {
                        if (state.groups.isNotEmpty() && !state.isImporting) {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.local_import_add_more),
                                        icon = MaterialSymbols.Rounded.Add,
                                        onClick = pick,
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            bottomBar = {
                if (state.groups.isNotEmpty()) {
                    ImportBar(
                        addToLibrary = state.addToLibrary,
                        isImporting = state.isImporting,
                        onAddToLibrary = viewModel::setAddToLibrary,
                        onImport = viewModel::startImport,
                    )
                }
            },
        ) { padding ->
            when {
                state.groups.isEmpty() && state.result == null && !state.isReading && state.problems.isEmpty() ->
                    StartPrompt(padding, onPick = pick)
                else -> ImportList(
                    padding = padding,
                    state = state,
                    onRename = { renaming = it },
                    onType = viewModel::setType,
                    onRemove = viewModel::remove,
                    onOpen = { series ->
                        when {
                            series.type == ContentType.NOVELS ->
                                navigator.push(NovelScreen(LocalNovelSource.ID, series.path))
                            series.entryId != null -> navigator.push(MangaScreen(series.entryId))
                        }
                    },
                )
            }
        }

        renaming?.let { group ->
            RenameDialog(group.title, onDismiss = { renaming = null }) {
                viewModel.rename(group.key, it)
                renaming = null
            }
        }
        state.pickCategories?.let { pick ->
            ChangeCategoryDialog(
                initialSelection = pick.initialSelection,
                onDismissRequest = viewModel::dismissCategories,
                onEditCategories = { navigator.push(CategoryScreen()) },
                onConfirm = { include, _ -> viewModel.confirmCategories(pick.type, include) },
                title = stringResource(pick.type.labelRes),
            )
        }
    }
}

@Composable
private fun StartPrompt(padding: PaddingValues, onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(MaterialTheme.padding.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(MaterialTheme.padding.medium))
        Text(
            text = stringResource(MR.strings.local_import_formats),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MaterialTheme.padding.large))
        Button(onClick = onPick) { Text(stringResource(MR.strings.local_import_choose)) }
    }
}

@Composable
internal fun ImportList(
    padding: PaddingValues,
    state: LocalImportViewModel.State,
    onRename: (ImportGroup) -> Unit,
    onType: (String, ContentType) -> Unit,
    onRemove: (String) -> Unit,
    onOpen: (reikai.data.local.ImportedSeries) -> Unit,
) {
    LazyColumn(
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        if (state.isReading || state.isImporting) {
            item(key = "progress") {
                Column(Modifier.readableColumn().padding(MaterialTheme.padding.medium)) {
                    Text(
                        text = stringResource(if (state.isImporting) IMPORTING else READING),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(MaterialTheme.padding.small))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        state.result?.let { result ->
            item(key = "done") {
                Text(
                    text = pluralStringResource(MR.plurals.local_import_done, result.series.size, result.series.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.readableColumn().padding(MaterialTheme.padding.medium),
                )
            }
            items(result.series, key = { "done:${it.key}" }) { series ->
                Row(
                    modifier = Modifier.readableColumn().padding(horizontal = MaterialTheme.padding.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(series.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onOpen(series) }) { Text(stringResource(MR.strings.local_import_open)) }
                }
            }
        }
        items(state.groups, key = { it.key }) { group ->
            GroupCard(
                group = group,
                enabled = !state.isImporting,
                onRename = { onRename(group) },
                onType = { onType(group.key, it) },
                onRemove = { onRemove(group.key) },
            )
        }
        val problems = state.problems + state.result?.problems.orEmpty()
        if (problems.isNotEmpty()) {
            item(key = "skipped") {
                Text(
                    text = stringResource(MR.strings.local_import_skipped),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .readableColumn()
                        .padding(start = MaterialTheme.padding.medium, top = MaterialTheme.padding.medium),
                )
            }
            // By position: two files can share a name and a reason, and the list only grows.
            items(problems) { problem ->
                Column(
                    Modifier
                        .readableColumn()
                        .padding(horizontal = MaterialTheme.padding.medium)
                        .padding(vertical = MaterialTheme.padding.extraSmall),
                ) {
                    Text(
                        text = problem.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(problem.reason.label()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun GroupCard(
    group: ImportGroup,
    enabled: Boolean,
    onRename: () -> Unit,
    onType: (ContentType) -> Unit,
    onRemove: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.readableColumn().padding(horizontal = MaterialTheme.padding.medium),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(Modifier.padding(MaterialTheme.padding.medium)) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.width(64.dp).aspectRatio(2f / 3f),
            ) {
                if (group.cover != null) {
                    AsyncImage(
                        model = group.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.padding.medium),
            ) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                group.author?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val count = pluralStringResource(MR.plurals.local_import_file_count, group.files.size, group.files.size)
                Text(
                    // A series that cannot change type says which it is; one that can shows the choice below.
                    text = if (group.canBeEitherType) count else "$count · ${stringResource(group.type.labelRes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (group.canBeEitherType) {
                    // Wraps, so a large font size moves a chip to the next line instead of crushing it.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                        listOf(ContentType.NOVELS, ContentType.MANGA).forEach { type ->
                            FilterChip(
                                selected = group.type == type,
                                onClick = { onType(type) },
                                enabled = enabled,
                                label = { Text(stringResource(type.labelRes)) },
                            )
                        }
                    }
                }
            }
            Column {
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(MaterialSymbols.Rounded.Close, contentDescription = stringResource(MR.strings.action_remove))
                }
                IconButton(onClick = onRename, enabled = enabled) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Edit,
                        contentDescription = stringResource(MR.strings.local_import_series_name),
                    )
                }
            }
        }
        if (group.files.size > 1) {
            Column(
                Modifier.padding(
                    start = MaterialTheme.padding.medium,
                    end = MaterialTheme.padding.medium,
                    bottom = MaterialTheme.padding.medium,
                ),
            ) {
                group.files.forEach { file ->
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportBar(
    addToLibrary: Boolean,
    isImporting: Boolean,
    onAddToLibrary: (Boolean) -> Unit,
    onImport: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            // Clear of the navigation bar, so a swipe home cannot land on the switch or Import.
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(end = MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwitchPreferenceWidget(
                modifier = Modifier.weight(1f),
                title = stringResource(MR.strings.add_to_library),
                checked = addToLibrary,
                enabled = !isImporting,
                onCheckedChanged = onAddToLibrary,
            )
            Button(onClick = onImport, enabled = !isImporting) { Text(stringResource(MR.strings.local_import_start)) }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    NameDialog(
        title = stringResource(MR.strings.local_import_series_name),
        current = current,
        confirmText = stringResource(MR.strings.action_ok),
        onDismissRequest = onDismiss,
        onConfirm = onConfirm,
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

private val IMPORTING = MR.strings.local_import_importing
private val READING = MR.strings.local_import_reading

private fun ImportProblem.Reason.label() = when (this) {
    ImportProblem.Reason.Unreadable -> MR.strings.local_import_reason_unreadable
    ImportProblem.Reason.Protected -> MR.strings.local_import_reason_protected
    ImportProblem.Reason.Unsupported -> MR.strings.local_import_reason_unsupported
    ImportProblem.Reason.NoStorage -> MR.strings.local_import_reason_no_storage
    ImportProblem.Reason.CopyFailed -> MR.strings.local_import_reason_copy_failed
    ImportProblem.Reason.AddFailed -> MR.strings.local_import_reason_add_failed
}
