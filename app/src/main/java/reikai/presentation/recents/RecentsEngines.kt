package reikai.presentation.recents

import androidx.compose.runtime.Composable
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
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
    val manga = metroViewModel<UpdatesViewModel>()
    val novel = metroViewModel<NovelUpdatesViewModel>()
    return recentsEngine(RecentsSurface.UPDATES, setOf(RecentsMode.UPDATES)) {
        listOf(MangaRecentsAdapter.forUpdates(manga), NovelRecentsAdapter.forUpdates(novel))
    }
}

/** History: the read lane alone, so neither updates model is built. */
@Composable
fun rememberHistoryEngine(): RecentsEngine {
    val manga = metroViewModel<HistoryViewModel>()
    val novel = metroViewModel<NovelHistoryViewModel>()
    return recentsEngine(RecentsSurface.HISTORY, setOf(RecentsMode.HISTORY)) {
        listOf(MangaRecentsAdapter.forHistory(manga), NovelRecentsAdapter.forHistory(novel))
    }
}

/**
 * The combined tab: every lane, so all four models are built and every mode is declared. Declaring
 * them all is not optional, since the digest's section footers jump to a single-lane mode and the
 * engine refuses a mode its surface does not render.
 */
@Composable
fun rememberRecentsEngine(): RecentsEngine {
    val mangaUpdates = metroViewModel<UpdatesViewModel>()
    val novelUpdates = metroViewModel<NovelUpdatesViewModel>()
    val mangaHistory = metroViewModel<HistoryViewModel>()
    val novelHistory = metroViewModel<NovelHistoryViewModel>()
    return recentsEngine(RecentsSurface.RECENTS, RecentsMode.entries.toSet()) {
        listOf(
            MangaRecentsAdapter.forRecents(mangaUpdates, mangaHistory),
            NovelRecentsAdapter.forRecents(novelUpdates, novelHistory),
        )
    }
}

@Composable
private fun recentsEngine(
    surface: RecentsSurface,
    modes: Set<RecentsMode>,
    providers: () -> List<RecentsProvider>,
): RecentsEngine = assistedMetroViewModel<RecentsEngine, RecentsEngine.Factory> {
    create(providers = providers(), surface = surface, modes = modes)
}
