package reikai.presentation.selection

import eu.kanade.tachiyomi.data.download.model.Download
import reikai.domain.reader.ChapterProgress

/** What a selected chapter row tells the bulk bar, answered by every surface whose rows it serves. */
interface ChapterMarks {
    val read: Boolean
    val bookmark: Boolean

    /** Null where the row hides it; a read chapter answers [hasStarted] by being read. */
    val progress: ChapterProgress?
}

/**
 * Whether reading has begun, read from the stored value: a displayed label rounds a novel's
 * hundredths away, so it would call a chapter opened to half a percent unopened.
 */
val ChapterMarks.hasStarted: Boolean
    get() = read || progress?.hasStarted == true

/** Which bulk chapter actions a selection offers, one rule for every surface's bar. */
data class ChapterSelectionOffers(
    val bookmark: Boolean,
    val removeBookmark: Boolean,
    val markRead: Boolean,
    val markUnread: Boolean,
    val download: Boolean,
    val delete: Boolean,
)

/**
 * [downloads] holds each selected row's state, null where the row draws no download control.
 * Download is offered for anything not on disk yet, finished or not, as upstream's bars do.
 */
fun chapterSelectionOffers(chapters: List<ChapterMarks>, downloads: List<Download.State?>) = ChapterSelectionOffers(
    bookmark = chapters.any { !it.bookmark },
    // Guarded on non-empty: `all` is vacuously true over nothing, so a selection that answers for no
    // chapter would offer this one action and no other.
    removeBookmark = chapters.isNotEmpty() && chapters.all { it.bookmark },
    markRead = chapters.any { !it.read },
    markUnread = chapters.any { it.hasStarted },
    download = downloads.any { it != null && it != Download.State.DOWNLOADED },
    delete = downloads.any { it == Download.State.DOWNLOADED },
)
