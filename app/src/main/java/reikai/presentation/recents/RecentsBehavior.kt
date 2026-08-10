package reikai.presentation.recents

import reikai.domain.entry.EntryId
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult

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

    /**
     * What adding [entry] should do, before anything is written: already there, a possible duplicate to
     * ask about, or add outright. Null when the row has gone. Reads only, so the caller can favorite
     * between this and [applyAddCategories].
     */
    suspend fun addDecision(entry: EntryId): AddDecision<RecentsDuplicates>?

    /**
     * Adds [entry] through the shared add sequence, so a row adds the same way every other surface does.
     * Answers a category prompt rather than raising one: the engine owns the surface's one dialog slot,
     * so a provider never asks anything itself.
     */
    suspend fun addToLibrary(entry: EntryId): AddFavoriteResult

    /** The writes a category picker's confirm owes, in the shared order, once the user has chosen. */
    suspend fun applyAddCategories(entry: EntryId, categoryIds: List<Long>)

    /** Adds [entry] and merges it into the group of the [duplicates] the user picked. */
    suspend fun addToGroup(entry: EntryId, duplicates: List<EntryId>): AddFavoriteResult

    /** Drops every read record of this content type, behind the engine's one confirmation. */
    fun clearHistory()

    /**
     * Starts this type's library update, answering whether it started rather than was already running.
     * Each type has its own job, so the verb is per type and only the answer is combined.
     */
    fun refresh(): Boolean
}
