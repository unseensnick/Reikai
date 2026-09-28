package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCustomInfo
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.models.customInfo
import exh.metadata.metadata.base.FlatMetadata
import exh.metadata.sql.models.SearchMetadata
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import reikai.domain.category.CategoryContentType
import reikai.domain.category.byNamePreferring
import reikai.domain.library.ContentType
import reikai.domain.merge.RestoreMergeGroups
import tachiyomi.domain.backup.model.RestoredHistory
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import kotlin.time.Clock

@Inject
class MangaRestorer(
    private val restoreRepository: RestoreRepository,
    private val getCategories: GetCategories,
    private val fetchInterval: FetchInterval,
    // RK --> the merge groups and older custom info a backup carries outside its entries
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
    private val restoreMergeGroups: RestoreMergeGroups,
    private val setCustomMangaInfo: SetCustomMangaInfo,
    // RK <--
) {

    private val timeZone = TimeZone.currentSystemDefault()
    private val now = Clock.System.now().toLocalDateTime(timeZone)
    private val currentFetchWindow = fetchInterval.getWindow(now.date, timeZone)

    /**
     * Restores [backupMangas] all together, so either every one of them is restored or none is.
     */
    suspend fun restore(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) {
        // RK: a universal row may share a manga row's name
        val dbCategoriesByName = getCategories.await().byNamePreferring(CategoryContentType.MANGA)
        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val entries = backupMangas.map { backupManga ->
            RestoredManga(
                manga = backupManga.getMangaImpl(),
                chapters = backupManga.chapters.map { it.toChapterImpl() },
                categoryIds = backupManga.categories.mapNotNull { backupCategoryOrder ->
                    backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                        dbCategoriesByName[backupCategory.name]?.id
                    }
                },
                history = backupManga.history.map {
                    val history = it.getHistoryImpl()
                    RestoredHistory(it.url, history.readAt, history.readDuration)
                },
                tracks = backupManga.tracking.map { it.getTrackImpl() },
                excludedScanlators = backupManga.excludedScanlators,
                // RK --> adult gallery metadata and custom info, keyed to the restored id in the repository
                searchMetadata = backupManga.searchMetadata?.toFlatMetadata(),
                customInfo = backupManga.customInfo?.toCustomMangaInfo(mangaId = 0L),
                // RK <--
            )
        }

        restoreRepository.restoreManga(entries) {
            fetchInterval.withFetchInterval(it, now, timeZone, currentFetchWindow)
        }
    }

    // RK --> merge groups and custom info restore

    /**
     * RK: materialize the backup's manga merge groups into the merge_group tables once the manga have
     * been restored (their ids differ from the source device). Members resolve from the backup's stable
     * {url, source} refs; the shared [RestoreMergeGroups] decides the rest. Call this AFTER the manga
     * loop completes.
     */
    suspend fun restoreMerges(merges: List<BackupMangaMergeGroup>) {
        restoreMergeGroups(
            ContentType.MANGA,
            merges.map { group ->
                group.refs.mapNotNull { getMangaByUrlAndSourceId.await(it.url, it.source)?.id }
            },
        )
    }

    // RK: an older root-list row for a series the backup does not list, applied when the device has it
    suspend fun restoreCustomInfo(source: Long, url: String, info: BackupCustomInfo) {
        getMangaByUrlAndSourceId.await(url, source)?.let { setCustomMangaInfo.set(info.toCustomMangaInfo(it.id)) }
    }

    // RK: the novel twin is NovelRestorer.restoreCustomInfo; both read BackupCustomInfoFields.customInfo.
    private fun BackupCustomInfo.toCustomMangaInfo(mangaId: Long) = CustomMangaInfo(
        mangaId = mangaId,
        title = title,
        author = author,
        artist = artist,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnailUrl,
    )

    // RK: the id is a placeholder; the repository writes the rows under the restored entry's own
    private fun BackupSearchMetadata.toFlatMetadata() = FlatMetadata(
        metadata = SearchMetadata(
            mangaId = 0L,
            uploader = uploader,
            extra = extra,
            indexedExtra = indexedExtra,
            extraVersion = extraVersion,
        ),
        tags = tags.map {
            SearchTag(id = null, mangaId = 0L, namespace = it.namespace, name = it.name, type = it.type)
        },
        titles = titles.map { SearchTitle(id = null, mangaId = 0L, title = it.title, type = it.type) },
    )
    // RK <--
}
