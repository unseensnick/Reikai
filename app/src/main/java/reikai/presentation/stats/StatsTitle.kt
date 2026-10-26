package reikai.presentation.stats

import reikai.domain.entry.EntryId
import reikai.domain.recommendation.taste.TrackStatus
import kotlin.time.Instant

/**
 * One series as every Statistics chart and details grouping reads it, the same for both content types.
 * A merged series is one title: its sources, tags and trackers span every member, its chapters are the
 * stitch's, and its status is the lead's as in [StatsSeries].
 */
data class StatsTitle(
    val entryId: EntryId,
    val title: String,
    val status: Long,
    val sources: List<StatsSource>,
    val genres: List<String>,
    val categoryIds: List<Long>,
    val totalChapters: Long,
    val readChapters: Long,
    val trackerIds: List<Long>,
    val meanScore: Double?,
    val trackStatus: TrackStatus?,
    /** The earliest start a tracker holds for any member. */
    val trackerStartedAt: Instant?,
    /** Lifetime reading time from history, every member summed. */
    val readDuration: Long,
    /** When the reading log first saw any member read; null before the log existed. */
    val firstLoggedReadAt: Instant?,
)

/** A source as library search keys it ([reikai.presentation.library.LibraryQuerySource.key]). */
data class StatsSource(val key: String, val name: String, val language: String)

/** How one content type answers [StatsTitle]'s per-entry fields; the shape of `libraryItemQueryFields`. */
class StatsTitleFields<T>(
    val entryId: (T) -> EntryId,
    val title: (T) -> String,
    val status: (T) -> Long,
    val source: (T) -> StatsSource,
    val genres: (T) -> List<String>?,
    /** Edit info's tags, which replace every source's own, as the library search reads them. */
    val customGenres: (T) -> List<String>?,
    val categoryIds: (T) -> List<Long>,
    val totalChapters: (T) -> Long,
    val readCount: (T) -> Long,
)
