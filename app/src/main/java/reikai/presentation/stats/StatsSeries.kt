package reikai.presentation.stats

import eu.kanade.tachiyomi.data.track.Tracker
import reikai.domain.library.SmartUpdateFacts
import reikai.domain.merge.EntryMergeManager
import reikai.domain.merge.MergeBucket
import reikai.domain.merge.MergedGroupCounts
import reikai.domain.recommendation.taste.trackStatusOf
import reikai.presentation.library.groupStatusTrack
import reikai.presentation.library.libraryTrackerMeans
import reikai.presentation.library.mergedGroupTracks
import reikai.presentation.library.seriesGenres
import tachiyomi.domain.track.model.Track
import kotlin.time.Instant

/**
 * One content type's library as Statistics counts it. A merged series is one title, its first member
 * (the library lead, see `rankedSeriesBuckets`) standing for it, with its read and unread taken from the stored stitch
 * ([groupCounts], by group id) as its library card takes them. Per-source rows and files (chapters,
 * downloads) sum over every member and its trackers are read across them, as the library card reads them.
 */
class StatsSeries<T>(
    private val buckets: List<MergeBucket<T>>,
    private val groupCounts: Map<Long, MergedGroupCounts>,
    private val id: (T) -> Long,
) {

    val titles: List<T> = buckets.map { it.members.first() }

    val members: List<T> = buckets.flatMap { it.members }

    private val membersByTitle: Map<Long, List<Long>> =
        buckets.associate { bucket -> id(bucket.members.first()) to bucket.members.map(id) }

    /** Each member's title id, so a session on any source of a merged series counts under the series. */
    val titleIdByMember: Map<Long, Long> =
        membersByTitle.flatMap { (titleId, members) -> members.map { it to titleId } }.toMap()

    /** Mihon's completed count: titles whose status is completed and that have nothing left unread. */
    fun completedCount(facts: (T) -> SmartUpdateFacts): Int = buckets.count { bucket ->
        val title = facts(bucket.members.first())
        title.isCompleted && (stitchOf(bucket)?.unread ?: title.unreadCount) == 0L
    }

    /** Titles with a chapter read, as the library's Started filter reads them. */
    fun startedCount(facts: (T) -> SmartUpdateFacts): Int = buckets.count { bucket ->
        stitchOf(bucket)?.let { it.read > 0 } ?: facts(bucket.members.first()).hasStarted
    }

    /** Titles the global update reaches. Each source of a merged series is updated on its own, so the
     *  series counts once when [isUpdated] holds for any of them. */
    fun globalUpdateCount(isUpdated: (T) -> Boolean): Int = buckets.count { it.members.any(isUpdated) }

    fun trackedCount(tracksById: Map<Long, List<Track>>): Int =
        membersByTitle.values.count { mergedGroupTracks(it, tracksById).isNotEmpty() }

    /** Each scored title's mean 0-10 score over [trackers], the logged-in ones. */
    fun meanScores(tracksById: Map<Long, List<Track>>, trackers: Map<Long, Tracker>): Collection<Double> =
        libraryTrackerMeans(membersByTitle, tracksById, trackers).values

    /**
     * Each title as [StatsTitle]: [trackers] are the logged-in ones, [readDuration] and [firstLoggedRead]
     * are keyed by member id. Chapters come from the stitch where the group has one, as [completedCount].
     */
    fun statsTitles(
        fields: StatsTitleFields<T>,
        tracksById: Map<Long, List<Track>>,
        trackers: Map<Long, Tracker>,
        readDuration: Map<Long, Long>,
        firstLoggedRead: Map<Long, Instant>,
    ): List<StatsTitle> {
        val means = libraryTrackerMeans(membersByTitle, tracksById, trackers)
        return buckets.map { bucket ->
            val title = bucket.members.first()
            val memberIds = bucket.members.map(id)
            val groupTracks = mergedGroupTracks(memberIds, tracksById).filter { it.trackerId in trackers }
            val stitch = stitchOf(bucket)
            StatsTitle(
                entryId = fields.entryId(title),
                title = fields.title(title),
                status = fields.status(title),
                sources = bucket.members.map(fields.source).distinctBy { it.key },
                genres = seriesGenres(fields.customGenres(title), bucket.members.map(fields.genres)),
                categoryIds = fields.categoryIds(title),
                totalChapters = stitch?.total ?: fields.totalChapters(title),
                readChapters = stitch?.read ?: fields.readCount(title),
                trackerIds = groupTracks.map { it.trackerId },
                meanScore = means[id(title)],
                trackStatus = groupStatusTrack(groupTracks, trackers.keys)
                    ?.let { track -> trackers[track.trackerId]?.trackStatusOf(track.status) },
                trackerStartedAt = groupTracks.mapNotNull { it.startDate }.minOrNull(),
                trackerFinishedAt = groupTracks.mapNotNull { it.finishDate }.maxOrNull(),
                readDuration = memberIds.sumOf { readDuration[it] ?: 0L },
                firstLoggedReadAt = memberIds.mapNotNull(firstLoggedRead::get).minOrNull(),
            )
        }
    }

    // An unstitched group falls back to its title's own counts, as its library row does.
    private fun stitchOf(bucket: MergeBucket<T>): MergedGroupCounts? = bucket.groupId?.let(groupCounts::get)
}

/** [rank] orders a merged group's members as the library collapse does, so the series reads the lead the library shows. */
suspend fun <T> EntryMergeManager.statsSeries(
    library: List<T>,
    groupCounts: Map<Long, MergedGroupCounts>,
    rank: (members: List<T>, memberRanking: List<Long>) -> List<T> = { members, _ -> members },
    id: (T) -> Long,
): StatsSeries<T> = StatsSeries(rankedSeriesBuckets(library, id, rank), groupCounts, id)
