package reikai.domain.library

import dev.zacsweers.metro.Inject
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
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
) {

    suspend fun await(): List<LibraryExportRow> = libraryExportRows(
        manga = mangaMergeManager.seriesBuckets(getFavorites.await()) { it.id }.map { it.members.first() },
        novels = novelMergeManager.seriesBuckets(novelRepository.getFavorites()) { it.id }.map { it.members.first() },
    )
}

internal fun libraryExportRows(manga: List<Manga>, novels: List<Novel>): List<LibraryExportRow> =
    manga.map { LibraryExportRow(it.title, it.author, it.artist) } +
        novels.map { LibraryExportRow(it.title, it.author, it.artist) }
