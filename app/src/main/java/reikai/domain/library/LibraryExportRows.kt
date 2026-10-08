package reikai.domain.library

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import reikai.domain.entry.EntryCustomInfo
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.entry.withCustomInfo
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.EntryMergeManager
import reikai.domain.merge.MergedChapterUnitRepository
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
 * novels, with a merged series written once, as the member its library row leads on.
 */
@Inject
class GetLibraryExportRows(
    private val getFavorites: GetFavorites,
    private val novelRepository: NovelRepository,
    private val mangaMergeManager: MangaMergeManager,
    private val novelMergeManager: NovelMergeManager,
    private val mergedChapterUnitRepository: MergedChapterUnitRepository,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val getEntryCustomInfo: GetEntryCustomInfo,
) {

    suspend fun await(): List<LibraryExportRow> {
        val recognizedCounts = mergedChapterUnitRepository.getRecognizedChapterCountsAsFlow().first()
        val mangaSources = reikaiLibraryPreferences.preferredMangaSources.get()
        val novelSources = reikaiLibraryPreferences.preferredNovelSources.get()
        val novels = novelRepository.getLibraryNovelAsFlow().first()
        return libraryExportRows(
            manga = mangaMergeManager.leads(getFavorites.await(), Manga::id) { members, ranking ->
                mangaLibraryLead(members, ranking, mangaSources, recognizedCounts) { it }
            },
            novels = novelMergeManager.leads(novels, { it.novel.id }) { members, ranking ->
                novelLibraryLead(members, ranking, novelSources)
            }.map { it.novel },
            customInfo = getEntryCustomInfo.awaitAll(),
        )
    }

    private suspend fun <T> EntryMergeManager.leads(
        entries: List<T>,
        id: (T) -> Long,
        lead: (members: List<T>, memberRanking: List<Long>) -> T,
    ): List<T> = seriesBuckets(entries, id).map { bucket ->
        val members = bucket.members
        if (members.size == 1) return@map members.single()
        lead(members, overrideRankingMemberIds(id(members.first())))
    }
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
