package reikai.presentation.stats

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import reikai.domain.entry.EntryCustomInfo
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.asNovelCover
import reikai.presentation.library.withCustomInfo
import reikai.presentation.novel.details.NovelScreen
import reikai.util.runCatchingCancellable
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository

/** A series' details page: a manga by id, a novel by its stored source and url; null for a novel since removed. */
suspend fun statsDetailsScreen(entryId: EntryId, novelRepository: NovelRepository): Screen? = when (entryId) {
    is EntryId.Manga -> MangaScreen(entryId.rawId)
    is EntryId.Novel -> NovelScreen.forStored(entryId.rawId, novelRepository)
}

/**
 * The covers of [ids] for Coil, read by id because a reading-time list also holds series outside the
 * library. A series since removed has none.
 */
suspend fun statsCovers(
    ids: Collection<EntryId>,
    mangaRepository: MangaRepository,
    novelRepository: NovelRepository,
): Map<EntryId, Any> = ids.mapNotNull { id ->
    val cover = runCatchingCancellable {
        when (id) {
            is EntryId.Manga -> mangaRepository.getMangaById(id.rawId).asMangaCover()
            is EntryId.Novel -> novelRepository.getById(id.rawId)?.asNovelCover()
        }
    }.getOrNull()
    cover?.let { id to it }
}.toMap()

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
