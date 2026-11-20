package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId

/** Whether this row stands for a merge group of more than one source. */
val LibraryItem.isMerged: Boolean get() = relatedMangaIds.size > 1

/** Every source row this library row stands for: its merge group's members, or itself when unmerged. */
fun LibraryItem.memberIds(): List<Long> = relatedMangaIds.ifEmpty { listOf(id) }

/**
 * The members behind each of [ids], distinct, for a verb that has to reach every source of a merged row.
 * An id with no row here contributes nothing: the collapse drops members out of the list, so a missing
 * id is one the verb must not guess at.
 */
fun Map<Long, LibraryItem>.memberIdsOf(ids: Collection<Long>): List<Long> =
    ids.flatMap { this[it]?.memberIds().orEmpty() }.distinct()

fun Map<Long, LibraryItem>.anyMerged(ids: Collection<Long>): Boolean = ids.any { this[it]?.isMerged == true }

/**
 * Whether [item] is hidden from the library view: hiding any member hides the series, so a merged row
 * shows hidden through any of its sources. Members are raw ids of the row's own type, typed through
 * [LibraryItem.entryId], since manga and novel ids collide. The rule has twins where there is no row: the
 * badge's [reikai.data.library.hiddenWithGroups], pinned by HiddenFromLibraryTest, and the Updates feeds' SQL,
 * pinned by UpdatesFeedFilterConformanceTest.
 */
fun isHiddenFromLibrary(item: LibraryItem, hidden: Set<EntryId>): Boolean {
    if (hidden.isEmpty()) return false
    val type = item.entryId.contentType
    return item.memberIds().any { EntryId.of(type, it) in hidden }
}
