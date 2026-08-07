package eu.kanade.tachiyomi.ui.updates

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.updates.UpdatesDeleteConfirmationDialog
import eu.kanade.presentation.updates.UpdatesFilterDialog
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel.Event
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.feature.upcoming.UpcomingScreen
import reikai.data.novel.update.NovelUpdateJob
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.reader.NovelReaderScreen
import reikai.presentation.updates.NovelUpdatesViewModel
import reikai.presentation.updates.ReikaiUpdatesCategoryFilter
import reikai.presentation.updates.ReikaiUpdatesGroupToggle
import reikai.presentation.updates.ReikaiUpdatesScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object UpdatesTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_updates_enter)
            return TabOptions(
                index = 1u,
                title = stringResource(MR.strings.label_recent_updates),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(DownloadQueueScreen)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<UpdatesViewModel>()
        val settingsViewModel = viewModel<UpdatesSettingsViewModel>()
        val state by viewModel.state.collectAsState()
        // RK -->
        val novelViewModel = viewModel<NovelUpdatesViewModel>()
        val novelState by novelViewModel.state.collectAsState()
        val contentType by novelViewModel.contentType.collectAsState()
        val scope = rememberCoroutineScope()
        val chip: @Composable () -> Unit = {
            ContentTypeFilterChips(
                selected = contentType,
                onSelect = { type ->
                    // A selection must not survive into a chip that hides its rows: the action bar
                    // still counts the hidden entries and every action would run on them unseen.
                    // Mirrors the library engine's setContentType.
                    viewModel.toggleAllSelection(false)
                    novelViewModel.selectAll(false)
                    novelViewModel.setContentType(type)
                },
            )
        }

        // All three chips render through one consolidated Reikai screen. Manga is driven by Mihon's
        // untouched UpdatesViewModel (passed in), so its behavior is unchanged.
        ReikaiUpdatesScreen(
            contentType = contentType,
            mangaModel = viewModel,
            novelModel = novelViewModel,
            snackbarHostState = viewModel.snackbarHostState,
            chip = chip,
            onRefresh = {
                // Single-type chips keep their own model's started/already-running snackbar. The All chip
                // triggers both jobs directly (bypassing each model's event) so it shows one combined line.
                when (contentType) {
                    ContentType.MANGA -> viewModel.updateLibrary()
                    ContentType.NOVELS -> novelViewModel.updateLibrary()
                    ContentType.ALL -> {
                        val started = LibraryUpdateJob.startNow(context) or NovelUpdateJob.startNow(context)
                        scope.launch {
                            val msg = if (started) {
                                MR.strings.updating_both_libraries
                            } else {
                                MR.strings.update_already_running
                            }
                            viewModel.snackbarHostState.showSnackbar(context.stringResource(msg))
                        }
                    }
                }
            },
            onFilterClicked = viewModel::showFilterDialog,
            hasActiveFilters = state.hasActiveFilters,
            onCalendarClicked = { navigator.push(UpcomingScreen()) },
            onOpenMangaChapter = {
                // RK: Updates opens the chapter's own source list (source scope), not the whole group.
                context.startActivity(
                    ReaderActivity.newIntent(
                        context,
                        it.update.mangaId,
                        it.update.chapterId,
                        sourceScoped = true,
                    ),
                )
            },
            onClickMangaCover = { navigator.push(MangaScreen(it.update.mangaId)) },
            // RK: Updates opens the chapter's own source list (source scope), not the whole group.
            onOpenNovelChapter = {
                navigator.push(NovelReaderScreen(it.update.novelId, it.update.chapterId, sourceScoped = true))
            },
            onClickNovelCover = { navigator.push(NovelScreen(it.update.source, it.update.novelUrl)) },
        )

        // Filter / delete dialogs render regardless of chip (the filter is reachable from both screens).
        val onDismissDialog = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is UpdatesViewModel.Dialog.DeleteConfirmation -> {
                UpdatesDeleteConfirmationDialog(
                    onDismissRequest = onDismissDialog,
                    onConfirm = { viewModel.deleteChapters(dialog.toDelete) },
                )
            }
            is UpdatesViewModel.Dialog.FilterSheet -> {
                UpdatesFilterDialog(
                    onDismissRequest = onDismissDialog,
                    viewModel = settingsViewModel,
                    reikaiCategoryRow = {
                        ReikaiUpdatesCategoryFilter(viewModel = settingsViewModel, contentType = contentType)
                    },
                    reikaiAfterFilters = {
                        ReikaiUpdatesGroupToggle(viewModel = settingsViewModel)
                    },
                )
            }
            null -> {}
        }
        // RK <--

        LaunchedEffect(Unit) {
            viewModel.events.collectLatest { event ->
                when (event) {
                    Event.InternalError -> viewModel.snackbarHostState.showSnackbar(
                        context.stringResource(MR.strings.internal_error),
                    )
                    is Event.LibraryUpdateTriggered -> {
                        val msg = if (event.started) {
                            MR.strings.updating_library
                        } else {
                            MR.strings.update_already_running
                        }
                        viewModel.snackbarHostState.showSnackbar(context.stringResource(msg))
                    }
                }
            }
        }

        // RK: novel refresh feedback (started / already-running), shown on the shared snackbar host.
        LaunchedEffect(Unit) {
            novelViewModel.events.collectLatest { event ->
                when (event) {
                    is NovelUpdatesViewModel.Event.LibraryUpdateTriggered -> {
                        val msg = if (event.started) {
                            MR.strings.updating_library
                        } else {
                            MR.strings.update_already_running
                        }
                        viewModel.snackbarHostState.showSnackbar(context.stringResource(msg))
                    }
                }
            }
        }

        LaunchedEffect(state.selectionMode, novelState.selectionMode) {
            // RK: also hide the bottom nav during novel selection so its action bar has room.
            HomeScreen.showBottomNav(!state.selectionMode && !novelState.selectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }
        DisposableEffect(Unit) {
            viewModel.resetNewUpdatesCount()

            onDispose {
                viewModel.resetNewUpdatesCount()
            }
        }
    }
}
