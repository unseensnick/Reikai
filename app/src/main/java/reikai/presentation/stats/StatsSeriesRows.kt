package reikai.presentation.stats

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryCustomInfo
import reikai.domain.entry.EntryId
import reikai.domain.entry.withCustomInfo
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.asNovelCover
import reikai.domain.novel.model.withCustomInfo
import reikai.presentation.library.withCustomInfo
import reikai.util.runCatchingCancellable
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * The covers of [ids] for Coil. A library series draws its lead's, with its Edit info cover, as the library
 * does; one outside the library (a reading-time list holds those too) is read by id under its own cover,
 * as it is named by its own title. A series since removed has none.
 */
suspend fun statsCovers(
    ids: Collection<EntryId>,
    library: StatsLibrary,
    mangaRepository: MangaRepository,
    novelRepository: NovelRepository,
): Map<EntryId, Any> {
    val mangaLeads = library.manga.titles.associateBy { it.manga.id }
    val novelLeads = library.novels.titles.associateBy { it.novel.id }
    val leads = ids.associateWith { id ->
        val custom = library.customInfo[id]
        when (id) {
            is EntryId.Manga -> mangaLeads[id.rawId]?.manga?.withCustomInfo(custom)?.asMangaCover()
            is EntryId.Novel -> novelLeads[id.rawId]?.novel?.withCustomInfo(custom)?.asNovelCover()
        }
    }
    // The rest in one read per type, since a reading-time list can hold every series ever read.
    val outside = leads.filterValues { it == null }.keys
    val mangaIds = outside.filterIsInstance<EntryId.Manga>().map { it.rawId }
    val novelIds = outside.filterIsInstance<EntryId.Novel>().map { it.rawId }
    val stored = buildMap<EntryId, Any> {
        runCatchingCancellable { mangaRepository.getMangaByIds(mangaIds) }.getOrNull().orEmpty()
            .forEach { put(EntryId.Manga(it.id), it.asMangaCover()) }
        runCatchingCancellable { novelRepository.getByIds(novelIds) }.getOrNull().orEmpty()
            .forEach { put(EntryId.Novel(it.id), it.asNovelCover()) }
    }
    return ids.mapNotNull { id -> (leads[id] ?: stored[id])?.let { id to it } }.toMap()
}

/** The rows whose series is [state]; every row when [state] is null. */
fun List<LibraryItem>.inReadState(state: ReadState?, readStateOf: (EntryId) -> ReadState?): List<LibraryItem> =
    if (state == null) this else filter { readStateOf(it.entryId) == state }

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

/**
 * [this] in [comparator]'s order, then with each row's Edit info laid over it, so a custom title is drawn
 * but sorts where the library sorts it, by the source title.
 */
fun List<LibraryItem>.sortedForDisplay(
    comparator: Comparator<LibraryItem>,
    customInfo: Map<EntryId, EntryCustomInfo>,
): List<LibraryItem> = sortedWith(comparator).map { it.withCustomInfo(customInfo[it.entryId]) }
