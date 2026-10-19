package reikai.presentation.stats

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId

/** The title a series is counted under, read off any of its members. */
fun StatsLibrary.titleOf(member: EntryId): EntryId? = when (member) {
    is EntryId.Manga -> manga.titleIdByMember[member.rawId]?.let(EntryId::Manga)
    is EntryId.Novel -> novels.titleIdByMember[member.rawId]?.let(EntryId::Novel)
}

/**
 * The library rows standing for [titles]. A row is read through [titleOf], so a merged row still finds its
 * series when the library leads it on another member than the one Statistics reads it off.
 */
fun List<LibraryItem>.standingFor(titles: Set<EntryId>, titleOf: (EntryId) -> EntryId?): List<LibraryItem> =
    filter { titleOf(it.entryId) in titles }
