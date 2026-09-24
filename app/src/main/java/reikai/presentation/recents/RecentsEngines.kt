package reikai.presentation.recents

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
import mihon.app.di.appGraph
import reikai.domain.category.RecentsSurface
import reikai.presentation.history.NovelHistoryViewModel
import reikai.presentation.updates.NovelUpdatesViewModel

/*
 * One builder per rendered surface. Each resolves only the models its lanes need, which is the whole
 * point: a History tab that also built the two updates models would run their queries for a feed it
 * never shows. The adapters are built inline below rather than remembered, because only the first
 * viewModel() call for a store uses them and a remembered pair could outlive the engine holding it.
 */

/** Updates: the updated lane alone, so neither history model is built. */
@Composable
fun rememberUpdatesEngine(): RecentsEngine {
    val manga = mangaUpdatesModel(RecentsSurface.UPDATES)
    val novel = novelUpdatesModel(RecentsSurface.UPDATES)
    val graph = LocalContext.current.appGraph
    return recentsEngine(RecentsSurface.UPDATES, setOf(RecentsMode.UPDATES)) {
        listOf(
            graph.mangaRecentsAdapterFactory.forUpdates(manga),
            graph.novelRecentsAdapterFactory.forUpdates(novel),
        )
    }
}

/** History: the read lane alone, so neither updates model is built. */
@Composable
fun rememberHistoryEngine(): RecentsEngine {
    val manga = mangaHistoryModel(RecentsSurface.HISTORY)
    val novel = novelHistoryModel(RecentsSurface.HISTORY)
    val graph = LocalContext.current.appGraph
    return recentsEngine(RecentsSurface.HISTORY, setOf(RecentsMode.HISTORY)) {
        listOf(
            graph.mangaRecentsAdapterFactory.forHistory(manga),
            graph.novelRecentsAdapterFactory.forHistory(novel),
        )
    }
}

/**
 * The combined tab: every lane, so all four models are built and every mode is declared. Declaring
 * them all is not optional, since the digest's section footers jump to a single-lane mode and the
 * engine refuses a mode its surface does not render.
 */
@Composable
fun rememberRecentsEngine(): RecentsEngine {
    val mangaUpdates = mangaUpdatesModel(RecentsSurface.RECENTS)
    val novelUpdates = novelUpdatesModel(RecentsSurface.RECENTS)
    val mangaHistory = mangaHistoryModel(RecentsSurface.RECENTS)
    val novelHistory = novelHistoryModel(RecentsSurface.RECENTS)
    val graph = LocalContext.current.appGraph
    return recentsEngine(RecentsSurface.RECENTS, RecentsMode.entries.toSet()) {
        listOf(
            graph.mangaRecentsAdapterFactory.forRecents(mangaUpdates, mangaHistory),
            graph.novelRecentsAdapterFactory.forRecents(novelUpdates, novelHistory),
        )
    }
}

/*
 * The four feed models, each told whose category selection it reads. Only the first call for a store
 * builds one and later calls get that instance whatever they pass, so every call in a tab, the tab's
 * own beside its engine included, passes the surface that tab renders.
 */

@Composable
fun mangaUpdatesModel(surface: RecentsSurface): UpdatesViewModel =
    assistedMetroViewModel<UpdatesViewModel, UpdatesViewModel.Factory> { create(surface) }

@Composable
fun novelUpdatesModel(surface: RecentsSurface): NovelUpdatesViewModel =
    assistedMetroViewModel<NovelUpdatesViewModel, NovelUpdatesViewModel.Factory> { create(surface) }

@Composable
fun mangaHistoryModel(surface: RecentsSurface): HistoryViewModel =
    assistedMetroViewModel<HistoryViewModel, HistoryViewModel.Factory> { create(surface) }

@Composable
fun novelHistoryModel(surface: RecentsSurface): NovelHistoryViewModel =
    assistedMetroViewModel<NovelHistoryViewModel, NovelHistoryViewModel.Factory> { create(surface) }

@Composable
private fun recentsEngine(
    surface: RecentsSurface,
    modes: Set<RecentsMode>,
    providers: () -> List<RecentsProvider>,
): RecentsEngine = assistedMetroViewModel<RecentsEngine, RecentsEngine.Factory> {
    create(providers = providers(), surface = surface, modes = modes)
}
