package reikai.presentation.recents

import kotlinx.coroutines.flow.Flow
import reikai.domain.library.ContentType

/**
 * One content type's half of the recents surface: its three lane feeds plus the verbs in
 * [RecentsBehavior]. The lanes are separate flows because an engine collects only the lanes its modes
 * render, which is what stops the two-tab shape running every query twice.
 */
interface RecentsProvider : RecentsBehavior {
    val contentType: ContentType

    /** Entries with reading history, newest read first, one row per entry. */
    val readLane: Flow<List<RecentsItem>>

    /** Chapters fetched after the entry was added, newest first. */
    val updatedLane: Flow<List<RecentsItem>>

    /** Entries recently added to the library, newest first. */
    val addedLane: Flow<List<RecentsItem>>

    /**
     * The chapter a tap on [item] opens, resolved per lane: resume where you were on read, the first
     * unread of the burst on updated, the first unread on added. Null when nothing is left to open.
     *
     * Suspend and called per rendered row on purpose. Resolving at assembly would put one chapter
     * query per row on every emission, which on a five-hundred-row feed is the cost this surface is
     * being built to avoid. Merge-unaware for now on both types; that closes with merge collapse.
     */
    suspend fun targetChapter(item: RecentsItem): ChapterRef?
}
