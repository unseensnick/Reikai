package reikai.presentation.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.produceState
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
import reikai.data.local.CombinableSeries
import reikai.data.local.DuplicateChoice
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
        var adding by remember { mutableStateOf<ImportGroup?>(null) }

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
                        isAnswered = state.groups.none { it.asksAboutNewerCopies },
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
                    onSeparate = viewModel::setSeparate,
                    onDuplicateChoice = viewModel::setDuplicateChoice,
                    onNewerCopyChoice = viewModel::setNewerCopyChoice,
                    onAddTo = { adding = it },
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
        adding?.let { group ->
            AddToDialog(
                group = group,
                batch = state.groups.filter { it.key != group.key && it.type == group.type },
                load = { viewModel.librarySeries(group.type) },
                onDismiss = { adding = null },
                onGroup = {
                    viewModel.addToGroup(group.key, it)
                    adding = null
                },
                onSeries = {
                    viewModel.addToSeries(group.key, it)
                    adding = null
                },
            )
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
    onSeparate: (String, Boolean) -> Unit = { _, _ -> },
    onDuplicateChoice: (String, DuplicateChoice) -> Unit = { _, _ -> },
    onNewerCopyChoice: (String, DuplicateChoice) -> Unit = { _, _ -> },
    onAddTo: (ImportGroup) -> Unit = {},
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
                onSeparate = { onSeparate(group.key, it) },
                onDuplicateChoice = { onDuplicateChoice(group.key, it) },
                onNewerCopyChoice = { onNewerCopyChoice(group.key, it) },
                onAddTo = { onAddTo(group) },
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
    onSeparate: (Boolean) -> Unit = {},
    onDuplicateChoice: (DuplicateChoice) -> Unit = {},
    onNewerCopyChoice: (DuplicateChoice) -> Unit = {},
    onAddTo: () -> Unit = {},
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
                JoinLine(group, enabled, onSeparate, onAddTo)
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
        if (group.joins != null && group.duplicates.isNotEmpty()) {
            DuplicateChoices(
                pluralStringResource(MR.plurals.local_import_duplicates, group.duplicates.size, group.duplicates.size),
                group.onDuplicate,
                enabled,
                onDuplicateChoice,
            )
        }
        // Asked, with nothing picked for the user: a newer copy replaced keeps the old one's read progress, and
        // one kept beside it lists every chapter twice.
        if (group.joins != null && group.newerCopies.isNotEmpty()) {
            DuplicateChoices(
                pluralStringResource(
                    MR.plurals.local_import_newer_copies,
                    group.newerCopies.size,
                    group.newerCopies.size,
                ),
                group.onNewerCopy,
                enabled,
                onNewerCopyChoice,
            )
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

/** Where the group goes: the series already there it joins, or a new one, with the way to change it. */
@Composable
private fun JoinLine(group: ImportGroup, enabled: Boolean, onSeparate: (Boolean) -> Unit, onAddTo: () -> Unit) {
    group.existing?.let { existing ->
        Text(
            text = stringResource(
                if (group.separate) MR.strings.local_import_beside else MR.strings.local_import_joins,
                existing,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        if (group.existing != null) {
            TextButton(onClick = { onSeparate(!group.separate) }, enabled = enabled) {
                val toggle = MR.strings.run {
                    if (group.separate) local_import_add_to_it else local_import_keep_separate
                }
                Text(
                    stringResource(toggle),
                )
            }
        }
        TextButton(onClick = onAddTo, enabled = enabled) { Text(stringResource(MR.strings.action_add_to_series)) }
    }
}

/** Files the series it joins already holds in some form, as [notice] says, and whether to replace, keep both or skip them. */
@Composable
private fun DuplicateChoices(
    notice: String,
    selected: DuplicateChoice?,
    enabled: Boolean,
    onChoice: (DuplicateChoice) -> Unit,
) {
    Column(Modifier.padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.medium)) {
        Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            DuplicateChoice.entries.forEach { choice ->
                FilterChip(
                    selected = selected == choice,
                    onClick = { onChoice(choice) },
                    enabled = enabled,
                    label = { Text(stringResource(choice.label())) },
                )
            }
        }
    }
}

private fun DuplicateChoice.label() = when (this) {
    DuplicateChoice.Replace -> MR.strings.local_import_replace
    DuplicateChoice.KeepBoth -> MR.strings.local_import_keep_both
    DuplicateChoice.Skip -> MR.strings.local_import_skip
}

/** Pick another group of this import, or a series already in the library, for [group] to join. */
@Composable
private fun AddToDialog(
    group: ImportGroup,
    batch: List<ImportGroup>,
    load: suspend () -> List<CombinableSeries>,
    onDismiss: () -> Unit,
    onGroup: (key: String) -> Unit,
    onSeries: (folder: String) -> Unit,
) {
    val library by produceState<List<CombinableSeries>?>(null) { value = load() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.action_add_to_series)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                if (batch.isNotEmpty()) {
                    item { PickerHeader(stringResource(MR.strings.local_import_this_import)) }
                    items(batch, key = { "batch:${it.key}" }) { other ->
                        PickerRow(other.title) { onGroup(other.key) }
                    }
                }
                val series = library.orEmpty().filterNot { it.folder == group.joins }
                if (series.isNotEmpty()) {
                    item { PickerHeader(stringResource(MR.strings.label_library)) }
                    items(series, key = { "library:${it.id}" }) { one -> PickerRow(one.title) { onSeries(one.folder) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = MaterialTheme.padding.small),
    )
}

@Composable
private fun PickerRow(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

@Composable
private fun ImportBar(
    addToLibrary: Boolean,
    isImporting: Boolean,
    isAnswered: Boolean,
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
            Button(onClick = onImport, enabled = !isImporting && isAnswered) {
                Text(stringResource(MR.strings.local_import_start))
            }
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
