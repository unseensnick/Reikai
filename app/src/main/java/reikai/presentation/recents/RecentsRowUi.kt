package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.download.model.Download
import reikai.domain.reader.ChapterProgress

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
    /** Absent on the same lane, and for the same reason: no chapter, no state to hold. */
    val state: RecentsChapterState?,
)

/** A payload no adapter recognises, which only a lane added later could produce. */
val EMPTY_RECENTS_ROW =
    RecentsRowUi(cover = null, title = "", isFavorite = false, chapter = null, state = null)

/**
 * A row's live download state, handed over rather than recomputed: both engines already carry it on
 * the row they emit, which is the shape Mihon's own updates item uses. Kept off [RecentsRowUi] so that
 * stays an honestly immutable value; these are callbacks read during composition.
 */
data class RecentsDownloadUi(
    val state: () -> Download.State,
    val progress: RecentsDownloadProgress,
)

/**
 * How far a download has got, where the engine behind it can say. A typed slot rather than a number,
 * because one engine cannot answer at all and a zero there is indistinguishable from a download that
 * has genuinely made no progress.
 */
sealed interface RecentsDownloadProgress {
    data class Live(val percent: () -> Int) : RecentsDownloadProgress

    /** The novel downloader reports none until the two download subsystems merge. */
    data object Unsupported : RecentsDownloadProgress
}

/**
 * Everything a continue-reading row draws once its target is known: the chapter a tap will open,
 * rather than the one the history record was written from. All four fields describe that chapter, so
 * the row cannot name one while its dimming, its bookmark or its download control describe another,
 * which is what a half-move produced the first time. Not immutable, for [RecentsDownloadUi]'s reason.
 */
data class RecentsTargetRow(
    val ref: ChapterRef,
    val chapter: RecentsChapterUi,
    val state: RecentsChapterState,
    val download: RecentsDownloadUi,
)

/**
 * How a row labels its chapter, which is a display choice the lane makes: History names the chapter
 * by number and the time it was read, an update by the chapter's own name. Labelling only. What a
 * row's verbs act on is [RecentsChapterState], because both lanes name a real chapter.
 */
@Immutable
sealed interface RecentsChapterUi {
    /** How far into the series the reader is. Negative when the source numbered nothing. */
    data class Number(val value: Double) : RecentsChapterUi

    data class Named(val name: String) : RecentsChapterUi
}

/**
 * The chapter state a row's actions, icons and dimming read. Held apart from [RecentsChapterUi] since
 * the label a lane picks says nothing about what its chapter can do: keeping the two together is what
 * left a read row unable to say whether it was bookmarked, so every verb aimed at one did nothing.
 */
@Immutable
data class RecentsChapterState(
    val read: Boolean,
    val bookmark: Boolean,
    val progress: ChapterProgress?,
)

/**
 * A chapter's state, carrying the one rule both feeds share: progress shows only where reading
 * stopped short of the end. The replaced screens restated it four times over, once per content type
 * and again for a grouped row's children.
 */
fun chapterState(
    read: Boolean,
    bookmark: Boolean,
    progress: ChapterProgress,
): RecentsChapterState = RecentsChapterState(
    read = read,
    bookmark = bookmark,
    progress = progress.takeIf { !read },
)
