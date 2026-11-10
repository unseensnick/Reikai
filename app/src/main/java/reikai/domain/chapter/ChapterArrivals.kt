package reikai.domain.chapter

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * A chapter a sync is adding, with the state its source gave it; a null [dateUpload] means undated. [mark]
 * is the user's copy mark on its url, which a re-listed chapter keeps.
 */
data class ArrivingChapter(
    val number: Double,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val dateUpload: Instant? = null,
    val name: String = "Chapter $number",
    val scanlator: String? = null,
    val mark: CopyMark? = null,
)

/** A chapter already stored, or one the sync is removing. [dateFetch] is read only for a removed one. */
data class StoredChapter(
    val number: Double,
    val read: Boolean,
    val bookmark: Boolean = false,
    val dateFetch: Instant = Instant.fromEpochMilliseconds(0),
    val name: String = "Chapter $number",
    val scanlator: String? = null,
    val mark: CopyMark? = null,
)

/**
 * What an added chapter is stored as. [isChangedOrDuplicate] keeps it out of the new-chapter results;
 * [isRelisted] marks one re-listed under a number the sync removed, which was out already.
 */
data class Arrival(
    val read: Boolean,
    val bookmark: Boolean,
    val dateFetch: Instant,
    val dateUpload: Instant,
    val isChangedOrDuplicate: Boolean,
    val isRelisted: Boolean,
)

/**
 * Whether a sync's [arrivals] are a release the next update is counted from, upstream
 * `SyncChaptersWithSource`'s rule both syncs call. A first fetch ([hadChapters] false) lists the back
 * catalogue, and a re-listed chapter was out already. The manga sync passes only the arrivals whose
 * scanlator the user has not excluded.
 */
fun foundRelease(hadChapters: Boolean, arrivals: List<Arrival>): Boolean =
    hadChapters && arrivals.any { !it.isRelisted }

/**
 * Upstream `SyncChaptersWithSource`'s rules for the chapters a sync adds, which both syncs call. Fetch
 * dates count down from [now] in source order, and an undated chapter takes the latest date listed before
 * it, or [now]. With [markDuplicateAsRead], a copy of a stored read chapter, by [chapterCopySets] with the
 * user's marks, arrives read, so [stored] carries the numbers this sync leaves them with. One numbered
 * like a [removed] one takes its state and earliest fetch date, so a re-listed chapter is not new. An
 * unnumbered chapter (below zero) matches nothing.
 */
fun chapterArrivals(
    added: List<ArrivingChapter>,
    stored: List<StoredChapter>,
    removed: List<StoredChapter>,
    markDuplicateAsRead: Boolean,
    now: Instant,
): List<Arrival> {
    val read = stored.filter { it.read }
    val readByNumber = read.filter { isRecognizedChapterNumber(it.number) }.groupBy { it.number }
    // A hand-made group joins its chapters whatever their numbers.
    val readByGroup = read.filter { it.mark is CopyMark.Group }.groupBy { it.mark }
    val removedByNumber = removed.groupBy { it.number }
    // Keeps an older undated chapter from sorting above a newer dated one.
    var maxSeenUploadDate: Instant? = null
    return added.mapIndexed { index, chapter ->
        val dateUpload = chapter.dateUpload
            ?.also { maxSeenUploadDate = listOfNotNull(maxSeenUploadDate, it).max() }
            ?: maxSeenUploadDate
            ?: now
        val candidates = readByNumber[chapter.number].orEmpty() + readByGroup[chapter.mark].orEmpty()
        val duplicate = markDuplicateAsRead && candidates.isNotEmpty() && chapter.isCopyOfAny(candidates)
        val twins = removedByNumber[chapter.number].takeIf { isRecognizedChapterNumber(chapter.number) }
        if (twins == null) {
            val dateFetch = now + (added.size - index).milliseconds
            Arrival(chapter.read || duplicate, chapter.bookmark, dateFetch, dateUpload, duplicate, isRelisted = false)
        } else {
            Arrival(
                read = twins.any { it.read },
                bookmark = twins.any { it.bookmark },
                dateFetch = twins.minOf { it.dateFetch },
                dateUpload = dateUpload,
                isChangedOrDuplicate = true,
                isRelisted = true,
            )
        }
    }
}

private fun ArrivingChapter.isCopyOfAny(reads: List<StoredChapter>): Boolean =
    (
        listOf(Written(number, name, scanlator, mark)) +
            reads.distinct().map { Written(it.number, it.name, it.scanlator, it.mark) }
        )
        .chapterCopySets({ it.number }, { it.name }, { it.origin }, { 0L }, { it.mark })
        .first().size > 1

private data class Written(val number: Double, val name: String, val origin: String?, val mark: CopyMark?)
