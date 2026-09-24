package reikai.presentation.recents

import eu.kanade.presentation.manga.components.ChapterDownloadAction
import reikai.domain.entry.EntryId
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.expandToUnits
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult

/**
 * Acting on the chapters a feed shows, on every surface: each mode that declares a selection needs
 * these, so each type answers them from the graph rather than from a model only some surfaces build.
 * Every verb moves its own work off the main thread, since the engine dispatches where the tap did.
 */
interface RecentsChapterActions {
    /**
     * Marks read or unread, routed through this type's read interactor so delete-after-read fires.
     * Reaches every source of a merge group, as the details list does: a collapsed row stands for the
     * whole group, so marking it read on one source alone would leave the group part-read. Suspend
     * because reaching them means reading the group's stored stitch.
     */
    suspend fun markRead(chapters: Set<ChapterRef>, read: Boolean)

    /** Bookmarks, group-wide on the same rule as [markRead]. */
    suspend fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean)

    /**
     * Queues, expedites, cancels or deletes, per the action the row's own indicator raised. One verb
     * with the action rather than one per action: the indicator already speaks in these four cases,
     * and a bulk download is the same call with [ChapterDownloadAction.START].
     *
     * Acts on the named chapters alone, never the group's copies: the grouped sources carry the same
     * chapter, so downloading each copy would fetch it once per source.
     */
    suspend fun download(chapters: Set<ChapterRef>, action: ChapterDownloadAction)

    /** Deletes the group's copies, since a row reads as downloaded when any of them holds the file. */
    suspend fun deleteDownloads(chapters: Set<ChapterRef>)
}

/** The chapter ids of the refs of content type [T], so a mixed selection never reaches the other type. */
internal inline fun <reified T : EntryId> Set<ChapterRef>.ownChapterIds(): List<Long> =
    filter { it.entryId is T }.map { it.chapterId }

/**
 * [ownChapterIds] widened to every source's copy of the same merged chapters, off each entry's stored
 * [stitchOf], so a collapsed row's verb reaches the whole group the way the details list's does. One
 * kernel for both types' actions, pinned by `RecentsChapterActionsConformanceTest`.
 */
internal suspend inline fun <reified T : EntryId> Set<ChapterRef>.groupChapterIds(
    stitchOf: suspend (entryId: Long) -> List<ChapterUnit>,
): List<Long> =
    filter { it.entryId is T }
        .groupBy { it.entryId.rawId }
        .flatMap { (entryId, refs) -> expandToUnits(refs.mapTo(HashSet()) { it.chapterId }, stitchOf(entryId)) }
        .distinct()

/**
 * One content type's action verbs on recent activity, keyed neutrally so a mixed selection dispatches
 * without the caller knowing which engine answers. Every method takes what to act on rather than
 * reading a selection, because the selection belongs to the engine that can span both types.
 *
 * Carries no state, deliberately unlike `LibraryBehavior`, whose state flow the library's engine reads
 * a search query back out of. Reasoning: content-layer-recents-surface.md.
 */
interface RecentsBehavior {
    val chapterActions: RecentsChapterActions

    /** Drops every read record of these entries. Both types support it; History reaches it. */
    fun removeFromHistory(entries: Set<EntryId>)

    /**
     * Drops the one read record [item] stands for, leaving the entry's others. The per-row dialog has
     * offered both this and the entry-wide sweep above since before the takeover, so a shell that
     * carried only one of them would quietly narrow what the button does.
     */
    fun removeHistoryRecord(item: RecentsItem)

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

    /**
     * The writes a category picker's confirm owes, in the shared order, once the user has chosen: the
     * favorite, joining [joinGroup]'s group as one unit when the add came from [addToGroup], then filing.
     */
    suspend fun applyAddCategories(entry: EntryId, categoryIds: List<Long>, joinGroup: List<EntryId>)

    /** Adds [entry] and merges it into the group of the [duplicates] the user picked. */
    suspend fun addToGroup(entry: EntryId, duplicates: List<EntryId>): AddFavoriteResult

    /**
     * Drops every read record of this content type, behind the engine's one confirmation, answering
     * whether the wipe succeeded. The shell says so afterwards, and a surface that announced a clear
     * it had not managed would be the one message the user cannot check.
     */
    suspend fun clearHistory(): Boolean

    /**
     * Starts this type's library update, answering whether it started rather than was already running.
     * Goes to the job rather than through a model, so a surface that renders no updated lane can still
     * offer a refresh; the answer is combined by the engine and the message belongs to the shell.
     */
    fun refresh(): Boolean
}
