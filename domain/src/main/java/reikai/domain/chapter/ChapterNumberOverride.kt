package reikai.domain.chapter

import reikai.domain.library.ContentType

/**
 * A chapter number the user corrected, keyed by the chapter's owner and url so a refresh that deletes
 * and re-adds the row keeps it. The corrected [number] is also written on the chapter row itself, so
 * everything that reads a number reads it; [sourceNumber] is what the source says, which clearing
 * puts back.
 */
data class ChapterNumberOverride(val url: String, val number: Double, val sourceNumber: Double)

interface ChapterNumberOverrideRepository {
    suspend fun getByOwner(type: ContentType, ownerId: Long): Map<String, ChapterNumberOverride>

    /** Corrects the chapter at [url] to [number], keeping the source's own the first time. */
    suspend fun set(type: ContentType, ownerId: Long, url: String, number: Double)

    /** Puts the source's number back on the chapter at [url] and forgets the correction. */
    suspend fun clear(type: ContentType, ownerId: Long, url: String)

    /** Stores [overrides] as a backup carried them and writes each onto its chapter row. */
    suspend fun restore(type: ContentType, ownerId: Long, overrides: List<ChapterNumberOverride>)

    /** Records a new source number under corrections the source has since renumbered. */
    suspend fun updateSourceNumbers(type: ContentType, ownerId: Long, overrides: List<ChapterNumberOverride>)
}

/** A sync's chapters with each correction in place of the source's number, and the corrections whose
 *  source number moved, which the sync records so a later clear restores the current one. */
data class OverriddenChapters<T>(val chapters: List<T>, val moved: List<ChapterNumberOverride>)

/** One rule for both syncs: a corrected chapter keeps its correction whatever the source now says. */
fun <T> Map<String, ChapterNumberOverride>.appliedTo(
    chapters: List<T>,
    urlOf: (T) -> String,
    numberOf: (T) -> Double,
    withNumber: (T, Double) -> T,
): OverriddenChapters<T> {
    if (isEmpty()) return OverriddenChapters(chapters, emptyList())
    val moved = mutableListOf<ChapterNumberOverride>()
    val applied = chapters.map { chapter ->
        val override = this[urlOf(chapter)] ?: return@map chapter
        val sourceNumber = numberOf(chapter)
        if (sourceNumber != override.sourceNumber) moved += override.copy(sourceNumber = sourceNumber)
        withNumber(chapter, override.number)
    }
    return OverriddenChapters(applied, moved)
}

/** The corrections a backup's chapters carry: each one whose source number was written beside it. */
fun <T> backedUpOverrides(
    chapters: List<T>,
    urlOf: (T) -> String,
    numberOf: (T) -> Double,
    sourceNumberOf: (T) -> Double?,
): List<ChapterNumberOverride> =
    chapters.mapNotNull { chapter ->
        sourceNumberOf(chapter)?.let { ChapterNumberOverride(urlOf(chapter), numberOf(chapter), it) }
    }
