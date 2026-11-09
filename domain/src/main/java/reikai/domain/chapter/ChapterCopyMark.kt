package reikai.domain.chapter

import kotlinx.coroutines.flow.StateFlow
import reikai.domain.library.ContentType

/** A hand-made correction to [chapterCopySets] for one chapter, for when the rule folds or misses one. */
sealed interface CopyMark {
    /** Never folded into another chapter, whatever the rule says. */
    data object NeverSkip : CopyMark

    /** A copy of every chapter of its owner carrying the same [key], whatever their numbers. */
    data class Group(val key: Long) : CopyMark
}

/** The mark as a backup field carries it: 0 for Never skip, else the group's key, an epoch millisecond. */
fun CopyMark.toBackupValue(): Long = when (this) {
    CopyMark.NeverSkip -> 0L
    is CopyMark.Group -> key
}

/** The marks a backup's chapters carry, by url. */
fun <T> backedUpCopyMarks(chapters: List<T>, urlOf: (T) -> String, valueOf: (T) -> Long?): Map<String, CopyMark> =
    chapters.mapNotNull { chapter ->
        valueOf(chapter)?.let { urlOf(chapter) to if (it == 0L) CopyMark.NeverSkip else CopyMark.Group(it) }
    }.toMap()

/** A chapter's mark, keyed by its owner and url as [ChapterNumberOverride] is, so a refresh keeps it. */
data class ChapterCopyMark(val ownerId: Long, val url: String, val mark: CopyMark)

/**
 * One marked chapter as a report on the rule needs it. [source] is the source's id for manga and its key for
 * novels; the chapter fields are null once the source drops the chapter.
 */
data class CopyMarkReport(
    val title: String,
    val source: String,
    val url: String,
    val chapterName: String?,
    val chapterNumber: Double?,
    val scanlator: String?,
    val mark: CopyMark,
)

interface ChapterCopyMarkRepository {
    /** Every mark of [type] by owner, then url. The marks are made by hand, so the whole set is held. */
    fun marks(type: ContentType): StateFlow<Map<Long, Map<String, CopyMark>>>

    suspend fun set(type: ContentType, marks: List<ChapterCopyMark>)

    suspend fun clear(type: ContentType, ownerId: Long, urls: List<String>)

    suspend fun report(type: ContentType): List<CopyMarkReport>
}
