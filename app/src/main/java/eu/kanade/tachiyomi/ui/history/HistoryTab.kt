package eu.kanade.tachiyomi.ui.history

import android.content.Context
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import reikai.presentation.history.NovelHistoryViewModel
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.reader.NovelReaderScreen
import reikai.presentation.recents.RecentsScreen
import reikai.presentation.recents.rememberHistoryEngine
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object HistoryTab : Tab {

    private val snackbarHostState = SnackbarHostState()

    private val resumeLastChapterReadEvent = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_history_enter)
            return TabOptions(
                index = 2u,
                title = stringResource(MR.strings.label_recent_manga),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        resumeLastChapterReadEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val engine = rememberHistoryEngine()
        // RK: both models stay resolved for tab-reselect, which resumes the globally latest read and
        //     has no engine path. The engine builds these same two out of this tab's store.
        val viewModel = viewModel<HistoryViewModel>()
        val novelViewModel = viewModel<NovelHistoryViewModel>()

        RecentsScreen(
            engine = engine,
            title = stringResource(MR.strings.history),
            snackbarHostState = snackbarHostState,
        )

        val loaded = engine.assembled.collectAsState().value?.loading == false
        LaunchedEffect(loaded) {
            if (loaded) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            viewModel.events.collectLatest { e ->
                when (e) {
                    HistoryViewModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    // The screen announces a cleared history off its own dialog.
                    HistoryViewModel.Event.HistoryCleared -> Unit
                    is HistoryViewModel.Event.OpenChapter -> openChapter(context, e.chapter)
                }
            }
        }

        // RK --> novel history events
        LaunchedEffect(Unit) {
            novelViewModel.events.collectLatest { e ->
                when (e) {
                    NovelHistoryViewModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    NovelHistoryViewModel.Event.HistoryCleared -> Unit
                    is NovelHistoryViewModel.Event.OpenNovel ->
                        navigator.push(NovelScreen(e.source, e.url))
                    is NovelHistoryViewModel.Event.OpenChapter ->
                        if (e.chapterId != null) {
                            // RK: group scope (default) so a merged novel's prev/next spans every source
                            // instead of degrading to the one source of the history entry.
                            navigator.push(NovelReaderScreen(e.novelId, e.chapterId))
                        } else {
                            snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                        }
                }
            }
        }
        // RK <--

        LaunchedEffect(Unit) {
            resumeLastChapterReadEvent.receiveAsFlow().collectLatest {
                // RK: resume the globally-latest read across manga + novel. Both sides ask their own
                // unfiltered latest-entry query, so search text or a category filter cannot move what
                // resume opens. Whichever is newer wins.
                val mangaLatest = viewModel.getLast()
                val novelLatest = novelViewModel.getLast()
                val mangaAt = mangaLatest?.readAt?.time ?: Long.MIN_VALUE
                val novelAt = novelLatest?.readAt ?: Long.MIN_VALUE
                when {
                    novelLatest != null && novelAt >= mangaAt -> novelViewModel.resume(novelLatest)
                    mangaLatest != null -> viewModel.getNextChapterForManga(
                        mangaLatest.mangaId,
                        mangaLatest.chapterId,
                    )
                    else -> openChapter(context, null)
                }
            }
        }
    }

    private suspend fun openChapter(context: Context, chapter: Chapter?) {
        if (chapter != null) {
            val intent = ReaderActivity.newIntent(context, chapter.mangaId, chapter.id)
            context.startActivity(intent)
        } else {
            snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
        }
    }
}
