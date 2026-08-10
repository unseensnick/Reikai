package reikai.presentation.recents

import androidx.compose.runtime.Immutable

/**
 * What one recents row draws, answered by the provider that owns the entry so the shared layer never
 * unwraps a payload. Raw values only: the timestamp lives on the item and a progress label depends on
 * resources, so both are formatted where they are rendered.
 */
@Immutable
data class RecentsRowUi(
    val cover: Any?,
    val title: String,
    val isFavorite: Boolean,
    /** Absent on the newly-added lane, whose rows have no chapter at all. */
    val chapter: RecentsChapterUi?,
)

/** A payload no adapter recognises, which only a lane added later could produce. */
val EMPTY_RECENTS_ROW = RecentsRowUi(cover = null, title = "", isFavorite = false, chapter = null)

/**
 * The chapter half of a row, shaped by the lane it came from: the read feed stores a chapter number
 * and no name, the updated feed the reverse. One flat type would need a sentinel for whichever half
 * a given feed does not carry, which is what the two screens do today.
 */
@Immutable
sealed interface RecentsChapterUi {
    /** How far into the series the reader is. Negative when the source numbered nothing. */
    data class Number(val value: Double) : RecentsChapterUi

    data class Named(
        val name: String,
        val read: Boolean,
        val bookmark: Boolean,
        val progress: RecentsProgress?,
    ) : RecentsChapterUi
}

/**
 * How far into a chapter the reader got, in the engine's own unit, so nothing shared has to know what
 * a page or a scroll position is. Whether a value rounds away to nothing is the renderer's call, since
 * only it knows how the number is written out.
 */
@Immutable
sealed interface RecentsProgress {
    data class Pages(val lastPageRead: Long) : RecentsProgress

    /** Hundredths of a percent, the unit the novel reader stores. */
    data class Percent(val hundredths: Long) : RecentsProgress
}

/**
 * A named chapter row, carrying the one rule both feeds share: progress shows only where reading
 * stopped short of the end. Both screens restate that rule today, once per content type and again for
 * a grouped row's children.
 */
fun namedChapter(
    name: String,
    read: Boolean,
    bookmark: Boolean,
    progress: RecentsProgress,
): RecentsChapterUi.Named = RecentsChapterUi.Named(
    name = name,
    read = read,
    bookmark = bookmark,
    progress = progress.takeIf { !read },
)
