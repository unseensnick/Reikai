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
import cafe.adriel.voyager.core.model.rememberScreenModel
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
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel.Event
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.feature.upcoming.UpcomingScreen
import reikai.data.novel.update.NovelUpdateJob
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.reader.NovelReaderScreen
import reikai.presentation.updates.NovelUpdatesScreenModel
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
        val screenModel = rememberScreenModel { UpdatesScreenModel() }
        val settingsScreenModel = rememberScreenModel { UpdatesSettingsScreenModel() }
        val state by screenModel.state.collectAsState()
        // RK -->
        val novelScreenModel = rememberScreenModel { NovelUpdatesScreenModel() }
        val novelState by novelScreenModel.state.collectAsState()
        val contentType by novelScreenModel.contentType.collectAsState()
        val scope = rememberCoroutineScope()
        val chip: @Composable () -> Unit = {
            ContentTypeFilterChips(selected = contentType, onSelect = novelScreenModel::setContentType)
        }

        // All three chips render through one consolidated Reikai screen. Manga is driven by Mihon's
        // untouched UpdatesScreenModel (passed in), so its behavior is unchanged.
        ReikaiUpdatesScreen(
            contentType = contentType,
            mangaModel = screenModel,
            novelModel = novelScreenModel,
            snackbarHostState = screenModel.snackbarHostState,
            chip = chip,
            onRefresh = {
                // Single-type chips keep their own model's started/already-running snackbar. The All chip
                // triggers both jobs directly (bypassing each model's event) so it shows one combined line.
                when (contentType) {
                    ContentType.MANGA -> screenModel.updateLibrary()
                    ContentType.NOVELS -> novelScreenModel.updateLibrary()
                    ContentType.ALL -> {
                        val started = LibraryUpdateJob.startNow(context) or NovelUpdateJob.startNow(context)
                        scope.launch {
                            val msg = if (started) {
                                MR.strings.updating_both_libraries
                            } else {
                                MR.strings.update_already_running
                            }
                            screenModel.snackbarHostState.showSnackbar(context.stringResource(msg))
                        }
                    }
                }
            },
            onFilterClicked = screenModel::showFilterDialog,
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
        val onDismissDialog = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is UpdatesScreenModel.Dialog.DeleteConfirmation -> {
                UpdatesDeleteConfirmationDialog(
                    onDismissRequest = onDismissDialog,
                    onConfirm = { screenModel.deleteChapters(dialog.toDelete) },
                )
            }
            is UpdatesScreenModel.Dialog.FilterSheet -> {
                UpdatesFilterDialog(
                    onDismissRequest = onDismissDialog,
                    screenModel = settingsScreenModel,
                    reikaiCategoryRow = {
                        ReikaiUpdatesCategoryFilter(screenModel = settingsScreenModel, contentType = contentType)
                    },
                    reikaiAfterFilters = {
                        ReikaiUpdatesGroupToggle(screenModel = settingsScreenModel)
                    },
                )
            }
            null -> {}
        }
        // RK <--

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    Event.InternalError -> screenModel.snackbarHostState.showSnackbar(
                        context.stringResource(MR.strings.internal_error),
                    )
                    is Event.LibraryUpdateTriggered -> {
                        val msg = if (event.started) {
                            MR.strings.updating_library
                        } else {
                            MR.strings.update_already_running
                        }
                        screenModel.snackbarHostState.showSnackbar(context.stringResource(msg))
                    }
                }
            }
        }

        // RK: novel refresh feedback (started / already-running), shown on the shared snackbar host.
        LaunchedEffect(Unit) {
            novelScreenModel.events.collectLatest { event ->
                when (event) {
                    is NovelUpdatesScreenModel.Event.LibraryUpdateTriggered -> {
                        val msg = if (event.started) {
                            MR.strings.updating_library
                        } else {
                            MR.strings.update_already_running
                        }
                        screenModel.snackbarHostState.showSnackbar(context.stringResource(msg))
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
            screenModel.resetNewUpdatesCount()

            onDispose {
                screenModel.resetNewUpdatesCount()
            }
        }
    }
}
