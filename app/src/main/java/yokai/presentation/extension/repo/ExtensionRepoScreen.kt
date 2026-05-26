package yokai.presentation.extension.repo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExtensionOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalDialogHostState
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.isTablet
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import yokai.domain.DialogHostState
import yokai.domain.extension.repo.model.ExtensionRepo
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.presentation.component.EmptyScreen
import yokai.presentation.component.ToolTipButton
import yokai.presentation.core.enterAlwaysAppBarScrollBehavior
import yokai.presentation.extension.repo.component.ExtensionRepoInput
import yokai.presentation.extension.repo.component.ExtensionRepoItem
import yokai.presentation.extension.repo.component.LnRepoItem
import yokai.util.Screen
import android.R as AR

class ExtensionRepoScreen(
    private val title: String,
    private var repoUrl: String? = null,
): Screen() {
    @Composable
    override fun Content() {
        val onBackPress = LocalBackPress.currentOrThrow
        val context = LocalContext.current
        val alertDialog = LocalDialogHostState.currentOrThrow

        val scope = rememberCoroutineScope()
        // Both tab screen models live at this Screen scope so a tab switch preserves state.
        // Same pattern as the Phase 8 library tabbed shell.
        val mangaScreenModel = rememberScreenModel { ExtensionRepoScreenModel() }
        val novelScreenModel = rememberScreenModel { LnRepoScreenModel() }
        val mangaState by mangaScreenModel.state.collectAsState()
        val novelState by novelScreenModel.state.collectAsState()

        var activeTab by rememberSaveable { mutableIntStateOf(0) }
        var mangaInputText by remember { mutableStateOf("") }
        var novelInputText by remember { mutableStateOf("") }
        val mangaListState = rememberLazyListState()
        val novelListState = rememberLazyListState()

        // Scroll behavior anchored to whichever tab is currently shown so the AppBar collapses
        // when the active tab scrolls, not when the inactive tab's content has scrolled past
        // its origin.
        val canScroll: () -> Boolean = {
            if (activeTab == 0) {
                mangaListState.firstVisibleItemIndex > 0 || mangaListState.firstVisibleItemScrollOffset > 0
            } else {
                novelListState.firstVisibleItemIndex > 0 || novelListState.firstVisibleItemScrollOffset > 0
            }
        }

        YokaiScaffold(
            onNavigationIconClicked = onBackPress,
            title = title,
            appBarType = AppBarType.SMALL,
            scrollBehavior = enterAlwaysAppBarScrollBehavior(canScroll = canScroll),
            actions = {
                ToolTipButton(
                    toolTipLabel = stringResource(MR.strings.refresh),
                    icon = Icons.Outlined.Refresh,
                    buttonClicked = {
                        // Refresh only applies to the manga tab today (LN side has no
                        // batch-update concept). Surface a single affordance + scope its action.
                        if (activeTab == 0) {
                            context.toast("Refreshing...")
                            mangaScreenModel.refreshRepos()
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                PrimaryTabRow(selectedTabIndex = activeTab) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text(stringResource(MR.strings.manga)) },
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = { Text(stringResource(MR.strings.light_novels)) },
                    )
                }
                when (activeTab) {
                    0 -> MangaRepoTab(
                        state = mangaState,
                        inputText = mangaInputText,
                        listState = mangaListState,
                        onInputChange = { mangaInputText = it },
                        onAddClick = { mangaScreenModel.addRepo(it) },
                        onDeleteClick = { repoToDelete ->
                            scope.launch {
                                alertDialog.awaitExtensionRepoDeletePrompt(repoToDelete, mangaScreenModel)
                            }
                        },
                    )
                    else -> LnRepoTab(
                        state = novelState,
                        inputText = novelInputText,
                        listState = novelListState,
                        onInputChange = { novelInputText = it },
                        onAddClick = { novelScreenModel.addRepo(it) },
                        onDeleteClick = { repoToDelete ->
                            scope.launch {
                                alertDialog.awaitLnRepoDeletePrompt(repoToDelete, novelScreenModel)
                            }
                        },
                    )
                }
            }
            alertDialog.value?.invoke()
        }

        // Deep-link only applies to the manga tab (manga keiyoushi repos), so honor the
        // existing constructor param semantics + force the manga tab active on prefill.
        LaunchedEffect(repoUrl) {
            repoUrl?.let {
                activeTab = 0
                mangaScreenModel.addRepo(repoUrl!!)
                repoUrl = null
            }
        }

        LaunchedEffect(Unit) {
            mangaScreenModel.event.collectLatest { event ->
                when (event) {
                    is ExtensionRepoEvent.NoOp -> {}
                    is ExtensionRepoEvent.LocalizedMessage -> context.toast(event.stringRes)
                    is ExtensionRepoEvent.Success -> mangaInputText = ""
                    is ExtensionRepoEvent.ShowDialog -> {
                        when (event.dialog) {
                            is RepoDialog.Conflict -> {
                                alertDialog.awaitExtensionRepoReplacePrompt(
                                    oldRepo = event.dialog.oldRepo,
                                    newRepo = event.dialog.newRepo,
                                    onMigrate = { mangaScreenModel.replaceRepo(event.dialog.newRepo) },
                                )
                            }
                        }
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            novelScreenModel.event.collectLatest { event ->
                when (event) {
                    is LnRepoEvent.NoOp -> {}
                    is LnRepoEvent.LocalizedMessage -> context.toast(event.stringRes)
                    is LnRepoEvent.Success -> novelInputText = ""
                }
                if (event !is LnRepoEvent.NoOp) novelScreenModel.consumeEvent()
            }
        }
    }

    @Composable
    private fun MangaRepoTab(
        state: ExtensionRepoScreenModel.State,
        inputText: String,
        listState: androidx.compose.foundation.lazy.LazyListState,
        onInputChange: (String) -> Unit,
        onAddClick: (String) -> Unit,
        onDeleteClick: (String) -> Unit,
    ) {
        if (state is ExtensionRepoScreenModel.State.Loading) return
        val repos = (state as ExtensionRepoScreenModel.State.Success).repos
        LazyColumn(
            userScrollEnabled = true,
            verticalArrangement = Arrangement.Top,
            state = listState,
        ) {
            item {
                ExtensionRepoInput(
                    inputText = inputText,
                    inputHint = stringResource(MR.strings.label_add_repo),
                    onInputChange = onInputChange,
                    onAddClick = onAddClick,
                )
            }
            if (repos.isEmpty()) {
                item {
                    EmptyScreen(
                        modifier = Modifier.fillParentMaxSize(),
                        image = Icons.Filled.ExtensionOff,
                        message = stringResource(MR.strings.information_empty_repos),
                        isTablet = isTablet(),
                    )
                }
                return@LazyColumn
            }
            repos.forEach { repo ->
                item {
                    ExtensionRepoItem(
                        extensionRepo = repo,
                        onDeleteClick = onDeleteClick,
                    )
                }
            }
        }
    }

    @Composable
    private fun LnRepoTab(
        state: LnRepoScreenModel.State,
        inputText: String,
        listState: androidx.compose.foundation.lazy.LazyListState,
        onInputChange: (String) -> Unit,
        onAddClick: (String) -> Unit,
        onDeleteClick: (String) -> Unit,
    ) {
        if (state is LnRepoScreenModel.State.Loading) return
        val repos = (state as LnRepoScreenModel.State.Success).repos
        LazyColumn(
            userScrollEnabled = true,
            verticalArrangement = Arrangement.Top,
            state = listState,
        ) {
            item {
                ExtensionRepoInput(
                    inputText = inputText,
                    // Hint text reflects the LN plugin registry URL shape (lnreader's
                    // canonical `plugins.min.json` pattern).
                    inputHint = "https://.../plugins.min.json",
                    onInputChange = onInputChange,
                    onAddClick = onAddClick,
                )
            }
            if (repos.isEmpty()) {
                item {
                    EmptyScreen(
                        modifier = Modifier.fillParentMaxSize(),
                        image = Icons.Filled.ExtensionOff,
                        message = stringResource(MR.strings.information_empty_repos),
                        isTablet = isTablet(),
                    )
                }
                return@LazyColumn
            }
            repos.forEach { url ->
                item {
                    LnRepoItem(
                        repoUrl = url,
                        onDeleteClick = onDeleteClick,
                    )
                }
            }
        }
    }

    private suspend fun DialogHostState.awaitExtensionRepoReplacePrompt(
        oldRepo: ExtensionRepo,
        newRepo: ExtensionRepo,
        onMigrate: () -> Unit,
    ): Unit = dialog { cont ->
        AlertDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMigrate()
                        cont.cancel()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_replace_repo))
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(text = stringResource(AR.string.cancel))
                }
            },
            title = {
                Text(text = stringResource(MR.strings.action_replace_repo_title))
            },
            text = {
                Text(text = stringResource(MR.strings.action_replace_repo_message, newRepo.name, oldRepo.name))
            },
        )
    }

    private suspend fun DialogHostState.awaitExtensionRepoDeletePrompt(
        repoToDelete: String,
        screenModel: ExtensionRepoScreenModel,
    ): Unit = dialog { cont ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = stringResource(MR.strings.confirm_delete_repo_title),
                    fontStyle = MaterialTheme.typography.titleMedium.fontStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 24.sp,
                )
            },
            text = {
                Text(
                    text = stringResource(MR.strings.confirm_delete_repo, repoToDelete),
                    fontStyle = MaterialTheme.typography.bodyMedium.fontStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            },
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(
                    onClick = {
                        screenModel.deleteRepo(repoToDelete)
                        cont.cancel()
                    }
                ) {
                    Text(
                        text = stringResource(MR.strings.delete),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(
                        text = stringResource(MR.strings.cancel),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                    )
                }
            },
        )
    }

    private suspend fun DialogHostState.awaitLnRepoDeletePrompt(
        repoToDelete: String,
        screenModel: LnRepoScreenModel,
    ): Unit = dialog { cont ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = stringResource(MR.strings.confirm_delete_repo_title),
                    fontStyle = MaterialTheme.typography.titleMedium.fontStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 24.sp,
                )
            },
            text = {
                Text(
                    text = stringResource(MR.strings.confirm_delete_repo, repoToDelete),
                    fontStyle = MaterialTheme.typography.bodyMedium.fontStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            },
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(
                    onClick = {
                        screenModel.deleteRepo(repoToDelete)
                        cont.cancel()
                    }
                ) {
                    Text(
                        text = stringResource(MR.strings.delete),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }) {
                    Text(
                        text = stringResource(MR.strings.cancel),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                    )
                }
            },
        )
    }
}
