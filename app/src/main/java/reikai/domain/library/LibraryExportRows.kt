package reikai.domain.library

import dev.zacsweers.metro.Inject
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.dedupeByMergeGroup
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga

/** One library entry as the library list export writes it, whichever content type it is. */
data class LibraryExportRow(val title: String, val author: String?, val artist: String?)

/** Every favourite of both content types, in the rows the library list export writes. */
@Inject
class GetLibraryExportRows(
    private val getFavorites: GetFavorites,
    private val novelRepository: NovelRepository,
    private val mergeGroupRepository: MergeGroupRepository,
) {

    suspend fun await(): List<LibraryExportRow> = libraryExportRows(
        manga = getFavorites.await(),
        novels = novelRepository.getFavorites(),
        mangaGroups = mergeGroupRepository.getAllMemberships(ContentType.MANGA),
        novelGroups = mergeGroupRepository.getAllMemberships(ContentType.NOVELS),
    )
}

/**
 * Manga first, then novels, with a merged series written once as the library shows it. Each type is
 * ordered by id before the group pass so the member that represents a group is stable, as in Stats.
 */
internal fun libraryExportRows(
    manga: List<Manga>,
    novels: List<Novel>,
    mangaGroups: Map<Long, Long>,
    novelGroups: Map<Long, Long>,
): List<LibraryExportRow> =
    manga.sortedBy { it.id }.dedupeByMergeGroup(mangaGroups) { it.id }
        .map { LibraryExportRow(it.title, it.author, it.artist) } +
        novels.sortedBy { it.id }.dedupeByMergeGroup(novelGroups) { it.id }
            .map { LibraryExportRow(it.title, it.author, it.artist) }
