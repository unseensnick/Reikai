package reikai.presentation.stats

import eu.kanade.tachiyomi.data.track.Tracker
import reikai.domain.library.SmartUpdateFacts
import reikai.domain.merge.EntryMergeManager
import reikai.domain.merge.MergeBucket
import reikai.presentation.library.libraryTrackerMeans
import reikai.presentation.library.mergedGroupTracks
import tachiyomi.domain.track.model.Track

/**
 * One content type's library as Statistics counts it. A merged series is one title, its lowest-id
 * member standing for it in the status stats, while per-source rows and files (chapters, downloads)
 * sum over every member and its trackers are read across them, as the library card reads them.
 */
class StatsSeries<T>(private val buckets: List<MergeBucket<T>>, private val id: (T) -> Long) {

    val titles: List<T> = buckets.map { it.members.first() }

    val members: List<T> = buckets.flatMap { it.members }

    private val membersByTitle: Map<Long, List<Long>> =
        buckets.associate { bucket -> id(bucket.members.first()) to bucket.members.map(id) }

    /** Mihon's completed count: titles whose status is completed and that have nothing left unread. */
    fun completedCount(facts: (T) -> SmartUpdateFacts): Int =
        titles.count { title -> facts(title).let { it.isCompleted && it.unreadCount == 0L } }

    /** Titles the global update reaches. Each source of a merged series is updated on its own, so the
     *  series counts once when [isUpdated] holds for any of them. */
    fun globalUpdateCount(isUpdated: (T) -> Boolean): Int = buckets.count { it.members.any(isUpdated) }

    fun trackedCount(tracksById: Map<Long, List<Track>>): Int =
        membersByTitle.values.count { mergedGroupTracks(it, tracksById).isNotEmpty() }

    /** Each scored title's mean 0-10 score over [trackers], the logged-in ones. */
    fun meanScores(tracksById: Map<Long, List<Track>>, trackers: Map<Long, Tracker>): Collection<Double> =
        libraryTrackerMeans(membersByTitle, tracksById, trackers).values
}

suspend fun <T> EntryMergeManager.statsSeries(library: List<T>, id: (T) -> Long): StatsSeries<T> =
    StatsSeries(seriesBuckets(library, id), id)
