package reikai.domain.backup

import kotlin.math.max

/** A chapter's user state as a restore merges it; [progress] is the type's own resume position. */
data class RestoredChapterState(val read: Boolean, val bookmark: Boolean, val progress: Long)

/**
 * Folds a backup chapter onto the device's copy: read and bookmark from either side, and the further
 * progress, so a restore never rewinds reading the device already has. Mihon lets any non-zero backup
 * position win instead; both types take this rule, pinned by RestoreMergeConformanceTest.
 */
fun RestoredChapterState.foldBackup(backup: RestoredChapterState) = RestoredChapterState(
    read = read || backup.read,
    bookmark = bookmark || backup.bookmark,
    progress = max(progress, backup.progress),
)

/** The part of a bound track a restore takes from the backup. */
data class RestoredTrackLink(val remoteId: Long, val libraryId: Long?, val lastChapterRead: Double)

/**
 * Folds a backup track onto the device's track on the same tracker: the backup's remote link and the
 * further chapter read. Everything else stays the device's, since that row is the one synced with the
 * tracker and a track carries no version to say which side is newer. This is Mihon's manga rule.
 */
fun RestoredTrackLink.foldBackup(backup: RestoredTrackLink) = RestoredTrackLink(
    remoteId = backup.remoteId,
    libraryId = backup.libraryId,
    lastChapterRead = max(lastChapterRead, backup.lastChapterRead),
)

/** One backup history entry as a restore reads it; [readAt] is 0 for an entry the user removed. */
data class RestoredChapterHistory(val chapterUrl: String, val readAt: Long, val readDuration: Long)

/**
 * A backup can hold one entry per copy of a duplicated chapter; they are one chapter's history, so they
 * fold to the latest read and the total time before meeting the device's row (mihon 553762fae).
 */
fun List<RestoredChapterHistory>.foldHistoryCopies(): List<RestoredChapterHistory> =
    groupBy { it.chapterUrl }.map { (chapterUrl, copies) ->
        RestoredChapterHistory(chapterUrl, copies.maxOf { it.readAt }, copies.sumOf { it.readDuration })
    }
