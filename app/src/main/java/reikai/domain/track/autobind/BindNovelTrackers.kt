package reikai.domain.track.autobind

import dev.zacsweers.metro.Inject
import reikai.domain.novel.interactor.AddNovelTrack
import reikai.domain.novel.model.Novel
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext

/**
 * Binds a novel just added to the library to the trackers that know its source. Twin of
 * `AddTracks.bindEnhancedTrackers`; both run [bindOnAdd].
 */
@Inject
class BindNovelTrackers(
    private val autoBindTrackers: AutoBindTrackers,
    private val sourceManager: NovelSourceManager,
    private val addNovelTrack: AddNovelTrack,
) {

    suspend fun await(novel: Novel) = withNonCancellableContext {
        withIOContext {
            val trackers = autoBindTrackers.loggedIn().filter { it.tracker.supportsNovels }
            // Checked first, so an add with nothing to bind never resolves the source and loads plugins.
            if (trackers.isEmpty()) return@withIOContext
            val source = sourceManager.get(novel.source) ?: return@withIOContext
            bindOnAdd(AutoBindEntry.Novel(novel, source), trackers) { candidate, match ->
                addNovelTrack.bind(candidate.tracker, match, novel.id)
            }
        }
    }
}
