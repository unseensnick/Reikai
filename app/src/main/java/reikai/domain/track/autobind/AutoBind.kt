package reikai.domain.track.autobind

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.Source
import logcat.LogPriority
import reikai.novel.source.NovelSource
import tachiyomi.core.common.util.system.logcat
import reikai.domain.novel.model.Novel as NovelEntry
import tachiyomi.domain.manga.model.Manga as MangaEntry

/** An entry an auto-binding tracker is asked about, with the source it came from. */
sealed interface AutoBindEntry {
    val title: String

    data class Manga(val manga: MangaEntry, val source: Source) : AutoBindEntry {
        override val title: String get() = manga.title
    }

    data class Novel(val novel: NovelEntry, val source: NovelSource) : AutoBindEntry {
        override val title: String get() = novel.title
    }
}

/**
 * A tracker that binds an entry from a source it knows on its own, with no search: a manga server's own
 * tracker, or a site's tracker for the extension reading that site. Reikai's, for both content types,
 * so Mihon's [EnhancedTracker] stays as upstream has it; [EnhancedAutoBind] carries its trackers over.
 */
interface AutoBindTracker {
    val tracker: Tracker

    fun accepts(entry: AutoBindEntry): Boolean

    suspend fun match(entry: AutoBindEntry): TrackSearch?
}

/** A manga server's own tracker, which knows only the manga from its server's source. */
class EnhancedAutoBind(override val tracker: Tracker, private val enhanced: EnhancedTracker) : AutoBindTracker {

    override fun accepts(entry: AutoBindEntry): Boolean = entry is AutoBindEntry.Manga && enhanced.accept(entry.source)

    override suspend fun match(entry: AutoBindEntry): TrackSearch? =
        (entry as? AutoBindEntry.Manga)?.let { enhanced.match(it.manga) }
}

/** Each tracker's auto-binding, when it has one. */
@Inject
class AutoBindTrackers(private val trackerManager: TrackerManager) {

    fun loggedIn(): List<AutoBindTracker> = trackerManager.loggedInTrackers().mapNotNull(::of)

    fun of(tracker: Tracker): AutoBindTracker? =
        tracker as? AutoBindTracker ?: (tracker as? EnhancedTracker)?.let { EnhancedAutoBind(tracker, it) }
}

/**
 * Binds [entry] to each of [trackers] that accepts it and finds its match, through [bind], the entry
 * type's own write. One tracker failing is logged and stops none of the others.
 */
suspend fun bindOnAdd(
    entry: AutoBindEntry,
    trackers: List<AutoBindTracker>,
    bind: suspend (AutoBindTracker, TrackSearch) -> Unit,
) {
    trackers.filter { it.accepts(entry) }.forEach { candidate ->
        try {
            candidate.match(entry)?.let { bind(candidate, it) }
        } catch (e: Exception) {
            candidate.logcat(LogPriority.WARN, e) { "Could not match ${entry.title} with ${candidate.tracker.name}" }
        }
    }
}
