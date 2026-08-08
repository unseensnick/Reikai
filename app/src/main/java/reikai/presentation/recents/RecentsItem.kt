package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import reikai.domain.entry.EntryId

/**
 * A chapter, identified so it cannot be confused with another content type's. `chapters._id` and
 * `novel_chapters._id` are rowids in separate tables and overlap exactly the way entry ids do, so a
 * bare `Long` in a mixed feed cross-wires silently: a set of selected chapter ids would mark a novel
 * chapter because a manga chapter happened to share its number. Nothing crosses the provider seam
 * carrying a raw chapter id.
 */
@Immutable
data class ChapterRef(val entryId: EntryId, val chapterId: Long)

/**
 * Which activity put a row in the feed, and what chapter that activity was about. A lane is a typed
 * slot rather than an enum beside a nullable chapter id: only two of the three lanes have a chapter
 * at all, and a nullable id would make the absent case representable everywhere instead of only where
 * it happens.
 */
@Immutable
sealed interface RecentsLane {
    /** Read: the chapter the user last opened. Resumes where they were. */
    data class Read(val chapter: ChapterRef) : RecentsLane

    /** Updated: the newest chapter of the update burst, which is not necessarily what a tap opens. */
    data class Updated(val chapter: ChapterRef) : RecentsLane

    /** Added: the entry entered the library. There is no chapter, which is why this carries none. */
    data object Added : RecentsLane
}

/**
 * One row of recent activity, neutral over both content types. [timestamp] is epoch millis whatever
 * the source column was, normalised per adapter so the shared layer never meets a `Date` on one side
 * and a `Long` on the other. [payload] is the adapter's own row, carried and never inspected here;
 * anything the shared layer reasons about is a field or a typed slot, never read back out of it.
 */
@Immutable
data class RecentsItem(
    val entryId: EntryId,
    val timestamp: Long,
    val lane: RecentsLane,
    val payload: Any,
)
