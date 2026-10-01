package reikai.domain.dedupe

import reikai.domain.library.ContentType

/** A copy of an entry the upgrade's dedupe merged away, the entry it merged into, and the title it had. */
data class MergedDuplicate(
    val contentType: ContentType,
    val discardedId: Long,
    val survivorId: Long,
    val discardedTitle: String,
)

/** A chapter row the same dedupe merged into the row of its entry with the same url. */
data class MergedDuplicateChapter(val contentType: ContentType, val discardedId: Long, val survivorId: Long)

/** Each merged-away entry id of [contentType], to the id of the entry it merged into. */
fun List<MergedDuplicate>.survivorIds(contentType: ContentType): Map<Long, Long> =
    filter { it.contentType == contentType }.associate { it.discardedId to it.survivorId }

/** Each merged-away chapter id of [contentType], to the id of the chapter row it merged into. */
@JvmName("chapterSurvivorIds")
fun List<MergedDuplicateChapter>.survivorIds(contentType: ContentType): Map<Long, Long> =
    filter { it.contentType == contentType }.associate { it.discardedId to it.survivorId }

/**
 * The record 50.sqm and 51.sqm leave of the duplicates they merged, for the state the database does not
 * hold (custom cover files, download folders and queues, the pre-group merge prefs), which is still keyed
 * by the discarded id or title.
 */
interface MergedDuplicateRepository {

    suspend fun getAll(): List<MergedDuplicate>

    suspend fun getChapters(): List<MergedDuplicateChapter>

    /** Empties both records. */
    suspend fun clear()
}
