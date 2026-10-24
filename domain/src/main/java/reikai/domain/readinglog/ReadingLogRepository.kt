package reikai.domain.readinglog

import reikai.domain.library.ContentType
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The reading log: one row per reading session, kept beside history because history overwrites a
 * chapter's last read and so cannot say when its time was spent. [ContentType.ALL] is not a table and
 * throws. See `data/reading_session.sq`.
 */
interface ReadingLogRepository {

    /** Records a session of [duration] ms on [chapterId] ending at [endedAt]; a non-positive one is dropped. */
    suspend fun record(contentType: ContentType, chapterId: Long, endedAt: Instant, duration: Long)

    /**
     * Each of [entryIds]' sessions keyed by chapter url, the form a backup carries them in; an entry with no
     * session is absent.
     */
    suspend fun sessionsByUrl(contentType: ContentType, entryIds: List<Long>): Map<Long, List<UrlReadingSession>>

    /** Writes [sessions] onto [entryId]'s chapters with the same url; one whose chapter is gone is dropped. */
    suspend fun restore(contentType: ContentType, entryId: Long, sessions: List<UrlReadingSession>)

    /**
     * Moves each source chapter's sessions onto its target, keyed source id to target id ([sessionMoves]), so the time
     * counts once wherever the chapter went. A second run finds nothing left to move.
     */
    suspend fun move(contentType: ContentType, targetBySourceChapter: Map<Long, Long>)

    /** Every logged session, with the entry its chapter belongs to. */
    suspend fun sessions(contentType: ContentType): List<EntryReadingSession>

    /** Each entry's lifetime reading time from history, which predates the log; an entry never read is absent. */
    suspend fun readDurationByEntry(contentType: ContentType): Map<Long, Long>
}

data class UrlReadingSession(val chapterUrl: String, val endedAt: Instant, val duration: Long)

/**
 * A migration's session moves from its matched chapters, target id to source id in the target's order: each source
 * chapter goes to the first target matched to it, since a target list holding one number twice (two releases) would
 * otherwise count the same reading on each.
 */
fun sessionMoves(sourceByTarget: List<Pair<Long, Long>>): Map<Long, Long> = buildMap {
    sourceByTarget.forEach { (target, source) -> if (source !in this) put(source, target) }
}

/**
 * Whether a session is one the log keeps: some time spent, no more than a week of it, ending between the epoch and the
 * year 3000. A recorded one only ever fails the first; a restored one comes from a file, where a corrupt length or date
 * would have Statistics walk its days one at a time, or throw dating it.
 */
fun isLoggableSession(endedAt: Instant, duration: Long): Boolean =
    duration in 1..MAX_SESSION_MS && endedAt.toEpochMilliseconds() in 0..LATEST_SESSION_END_MS

private const val MAX_SESSION_MS = 7L * 24 * 60 * 60 * 1000

/** 3000-01-01T00:00:00Z. */
private const val LATEST_SESSION_END_MS = 32_503_680_000_000L

/** [title] is the entry's own, which names it when it is not in the library. */
data class EntryReadingSession(val entryId: Long, val title: String, val endedAt: Instant, val duration: Long) {
    val startedAt: Instant get() = endedAt - duration.milliseconds
}
