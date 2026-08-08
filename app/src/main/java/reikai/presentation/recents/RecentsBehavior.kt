package reikai.presentation.recents

import reikai.domain.entry.EntryId

/**
 * One content type's action verbs on recent activity, keyed neutrally so a mixed selection dispatches
 * without the caller knowing which engine answers. Every method takes what to act on rather than
 * reading a selection, because the selection belongs to the engine that can span both types.
 *
 * Carries no state, deliberately unlike `LibraryBehavior`, whose state flow the library's engine reads
 * a search query back out of. Reasoning: content-layer-recents-surface.md.
 */
interface RecentsBehavior {
    /** Marks read or unread, routed through this type's read interactor so delete-after-read fires. */
    fun markRead(chapters: Set<ChapterRef>, read: Boolean)

    fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean)

    fun download(chapters: Set<ChapterRef>)

    fun deleteDownloads(chapters: Set<ChapterRef>)

    /** Drops these entries' read records. Both types support it; History is where it is reachable. */
    fun removeFromHistory(entries: Set<EntryId>)

    /** Drops every read record of this content type, behind the engine's one confirmation. */
    fun clearHistory()
}
