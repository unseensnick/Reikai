package reikai.domain.backup

import kotlin.math.max

/**
 * Whether a restore takes the backup's source details over the device's: only when the backup fetched
 * them and the device never did, so a restore cannot roll back details the device refreshed since
 * (mihon c67a33f3d, which replaced the edit-count comparison).
 */
fun backupDetailsWin(deviceInitialized: Boolean, backupInitialized: Boolean): Boolean =
    backupInitialized && !deviceInitialized

/** The library date a restore keeps: the earlier of the two, where 0 means never added. */
fun earliestAddedAt(device: Long, backup: Long): Long = listOf(device, backup).filter { it > 0 }.minOrNull() ?: 0L

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

/**
 * What a backup track changes on the device's track on the same tracker: only a further chapter read.
 * The device's row, remote link included, is the one synced with the tracker, and taking the backup's
 * link would pair one remote entry's id with another's url and title (mihon 4b48a84ec). Null when the
 * backup is not ahead, so nothing is written.
 */
fun backupChapterReadAhead(device: Double, backup: Double): Double? = backup.takeIf { it > device }

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
