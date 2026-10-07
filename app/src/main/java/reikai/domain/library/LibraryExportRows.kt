package reikai.domain.library

import dev.zacsweers.metro.Inject
import reikai.domain.entry.EntryCustomInfo
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.entry.withCustomInfo
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.withCustomInfo
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga

/** One library entry as the library list export writes it, whichever content type it is. */
data class LibraryExportRow(val title: String, val author: String?, val artist: String?)

/**
 * Every favourite of both content types, in the rows the library list export writes: manga first, then
 * novels, with a merged series written once, as its lowest-id member, while the library shows it so.
 */
@Inject
class GetLibraryExportRows(
    private val getFavorites: GetFavorites,
    private val novelRepository: NovelRepository,
    private val mangaMergeManager: MangaMergeManager,
    private val novelMergeManager: NovelMergeManager,
    private val getEntryCustomInfo: GetEntryCustomInfo,
) {

    suspend fun await(): List<LibraryExportRow> = libraryExportRows(
        manga = mangaMergeManager.seriesBuckets(getFavorites.await()) { it.id }.map { it.members.first() },
        novels = novelMergeManager.seriesBuckets(novelRepository.getFavorites()) { it.id }.map { it.members.first() },
        customInfo = getEntryCustomInfo.awaitAll(),
    )
}

/** Each entry is written as the library shows it, under its Edit info overrides. */
internal fun libraryExportRows(
    manga: List<Manga>,
    novels: List<Novel>,
    customInfo: Map<EntryId, EntryCustomInfo>,
): List<LibraryExportRow> =
    manga.map { it.withCustomInfo(customInfo[EntryId.Manga(it.id)]) }
        .map { LibraryExportRow(it.title, it.author, it.artist) } +
        novels.map { it.withCustomInfo(customInfo[EntryId.Novel(it.id)]) }
            .map { LibraryExportRow(it.title, it.author, it.artist) }
