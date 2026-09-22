package reikai.domain.track.autobind

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.domain.novel.model.Novel
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga

/**
 * Binds an entry just added to the library to the trackers that know its source, off the add itself:
 * matching goes to the network, and the add files its categories straight after the favorite write, so
 * leaving the screen meanwhile must not cost the entry its categories or the binding.
 */
@Inject
@SingleIn(AppScope::class)
class AutoBindOnAdd(
    private val addTracks: AddTracks,
    private val bindNovelTrackers: BindNovelTrackers,
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> logcat(LogPriority.WARN, e) },
    )

    fun manga(manga: Manga, source: Source) {
        scope.launch { addTracks.bindEnhancedTrackers(manga, source) }
    }

    fun novel(novel: Novel) {
        scope.launch { bindNovelTrackers.await(novel) }
    }
}
