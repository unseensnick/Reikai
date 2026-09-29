package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem

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
