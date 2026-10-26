// Novel backup. Net-new Reikai file: the light-novel twin of MangaRestorer. Matches
// or inserts each novel by url+source, re-links its chapters (by url), categories (by name), tracks
// (by tracker id) and history (chapter url -> new chapter id), then materializes the merge groups into
// the merge_group tables from the backup's {url, source} refs once every novel has its fresh id.
// The two share their merge rules (RestoreMergeRules.kt), pinned by RestoreMergeConformanceTest.
package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCustomInfo
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelHistory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import eu.kanade.tachiyomi.data.backup.models.customInfo
import eu.kanade.tachiyomi.data.backup.models.toUrlReadingSession
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mihon.core.common.extensions.toInstantOrNull
import reikai.data.novel.novelFetchInterval
import reikai.domain.backup.RestoredChapterHistory
import reikai.domain.backup.RestoredChapterState
import reikai.domain.backup.backupChapterReadAhead
import reikai.domain.backup.backupDetailsWin
import reikai.domain.backup.foldBackup
import reikai.domain.backup.foldChapterCopies
import reikai.domain.backup.foldHistoryCopies
import reikai.domain.backup.restoredFavoriteAt
import reikai.domain.backup.restoredNextUpdate
import reikai.domain.category.CategoryContentType
import reikai.domain.category.byNamePreferring
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.chapter.backedUpOverrides
import reikai.domain.library.ContentType
import reikai.domain.library.ReleaseInterval
import reikai.domain.merge.PrefEraGrouping
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.interactor.SetCustomNovelInfo
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.readinglog.ReadingLogRepository
import tachiyomi.domain.category.model.NewCategory
import tachiyomi.domain.category.repository.CategoryRepository
import kotlin.time.Clock

@Inject
class NovelRestorer(
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val novelTrackRepository: NovelTrackRepository,
    private val restoreMergeGroups: RestoreMergeGroups,
    private val setCustomNovelInfo: SetCustomNovelInfo,
    private val novelHistoryRepository: NovelHistoryRepository,
    private val chapterNumberOverrides: ChapterNumberOverrideRepository,
    private val readingLog: ReadingLogRepository,
) {

    private val timeZone = TimeZone.currentSystemDefault()
    private val now = Clock.System.now().toLocalDateTime(timeZone)
    private val currentFetchWindow = ReleaseInterval.window(now.date, timeZone)

    /** Create any novel categories the backup has that the device doesn't, matched by name. */
    suspend fun restoreCategories(backupCategories: List<BackupNovelCategory>) {
        if (backupCategories.isEmpty()) return
        val dbCategoryNames = categoryRepository.getAll(CategoryContentType.NOVEL).mapTo(HashSet()) { it.name }
        // The database places each after every row of either library, and all of them or none land.
        categoryRepository.insertAll(
            backupCategories
                .filter { it.name !in dbCategoryNames }
                .sortedBy { it.order }
                .map { NewCategory(name = it.name, flags = it.flags, contentType = CategoryContentType.NOVEL) },
        )
    }

    suspend fun restore(backupNovel: BackupNovel, backupCategories: List<BackupNovelCategory>) {
        val dbNovel = novelRepository.getByUrlAndSource(backupNovel.url, backupNovel.source)
        val novel = backupNovel.toNovelImpl()

        val restored = if (dbNovel == null) {
            novel.copy(id = checkNotNull(novelRepository.insert(novel)) { "Failed to insert novel ${novel.url}" })
        } else {
            mergeNovel(novel, dbNovel).also {
                check(novelRepository.update(it)) { "Failed to update novel ${novel.url}" }
            }
        }
        val novelId = restored.id

        val chapters = restoreChapters(novelId, backupNovel.chapters)
        chapterNumberOverrides.restore(
            ContentType.NOVELS,
            novelId,
            backedUpOverrides(backupNovel.chapters, { it.url }, { it.chapterNumber }, { it.sourceChapterNumber }),
        )
        // Predicted from the restored chapters, keeping a next update still ahead, as manga's restore does.
        novelRepository.getById(novelId)?.let { stored ->
            val update = novelFetchInterval(stored, { chapters }, currentFetchWindow, timeZone, now).apply {
                nextUpdate = restoredNextUpdate(restored.nextUpdate, nextUpdate, currentFetchWindow)
            }
            novelRepository.update(update)
        }
        restoreCategoryMembership(novelId, backupNovel.categories, backupCategories)
        restoreTracks(novelId, backupNovel.tracking)
        restoreHistory(chapters, backupNovel.history)
        readingLog.restore(ContentType.NOVELS, novelId, backupNovel.readingSessions.map { it.toUrlReadingSession() })
        // An old backup carries the Last read stamp without history; after the history above, so a
        // backup that has history keeps it as is.
        backupNovel.lastReadAt?.toInstantOrNull()?.let { lastRead ->
            novelHistoryRepository.seedLastRead(novelId, lastRead)
        }
        // An entry without custom info leaves the device's own alone, as manga does, pinned by
        // BackupCustomInfoConformanceTest.
        backupNovel.customInfo?.let { restoreCustomInfo(novelId, it) }
    }

    /**
     * The device's row with the backup folded in by the rules manga's restore shares. User edits are not
     * in the row (they live in the custom_novel_info overlay, restored from the entry's custom fields).
     */
    private fun mergeNovel(novel: Novel, dbNovel: Novel): Novel {
        val details = if (backupDetailsWin(dbNovel.initialized, novel.initialized)) novel else dbNovel
        return dbNovel.copy(
            favoriteAt = restoredFavoriteAt(dbNovel.favoriteAt, novel.favoriteAt),
            title = details.title,
            author = details.author,
            artist = details.artist,
            description = details.description,
            genre = details.genre,
            status = details.status,
            thumbnailUrl = details.thumbnailUrl,
            updateStrategy = details.updateStrategy,
            initialized = dbNovel.initialized || novel.initialized,
            nextUpdate = dbNovel.nextUpdate ?: novel.nextUpdate,
        )
    }

    /** The novel's chapters as stored once the backup's are in, so the steps after this read no list. */
    private suspend fun restoreChapters(
        novelId: Long,
        backupChapters: List<BackupNovelChapter>,
    ): List<NovelChapter> {
        val dbChapters = novelChapterRepository.getByNovelId(novelId)
        val dbChaptersByUrl = dbChapters.associateBy { it.url }
        val inserted = mutableListOf<NovelChapter>()
        backupChapters.foldChapterCopies(
            url = { it.url },
            state = { RestoredChapterState(it.read, it.bookmark, it.lastTextProgress) },
            // A decoded backup chapter is this restore's own, so the kept copy takes the fold in place.
            withState = { kept, state ->
                kept.also {
                    it.read = state.read
                    it.bookmark = state.bookmark
                    it.lastTextProgress = state.progress
                }
            },
        ).forEach { backupChapter ->
            val incoming = backupChapter.toChapterImpl(novelId)
            val dbChapter = dbChaptersByUrl[backupChapter.url]
            if (dbChapter == null) {
                val id = checkNotNull(novelChapterRepository.insert(incoming)) {
                    "Failed to insert chapter ${incoming.url}"
                }
                inserted += incoming.copy(id = id)
            } else {
                // Keep the device's structural fields; only fold in read state from the backup.
                val readState = RestoredChapterState(dbChapter.read, dbChapter.bookmark, dbChapter.lastTextProgress)
                    .foldBackup(RestoredChapterState(incoming.read, incoming.bookmark, incoming.lastTextProgress))
                val merged = dbChapter.copy(
                    read = readState.read,
                    bookmark = readState.bookmark,
                    lastTextProgress = readState.progress,
                )
                if (merged != dbChapter) {
                    check(novelChapterRepository.update(merged)) { "Failed to update chapter ${merged.url}" }
                }
            }
        }
        return dbChapters + inserted
    }

    private suspend fun restoreCategoryMembership(
        novelId: Long,
        categoryOrders: List<Long>,
        backupCategories: List<BackupNovelCategory>,
    ) {
        if (categoryOrders.isEmpty()) return
        // Prefer the novel-typed row when a universal one shares its name, as manga's restore does.
        val dbCategoriesByName = categoryRepository.getAll(CategoryContentType.NOVEL)
            .byNamePreferring(CategoryContentType.NOVEL)
        val backupCategoriesByOrder = backupCategories.associateBy { it.order }
        val categoryIds = categoryOrders.mapNotNull { order ->
            backupCategoriesByOrder[order]?.let { dbCategoriesByName[it.name]?.id }
        }
        if (categoryIds.isNotEmpty()) {
            novelRepository.setCategories(novelId, categoryIds)
        }
    }

    private suspend fun restoreTracks(
        novelId: Long,
        backupTracks: List<BackupNovelTracking>,
    ) {
        if (backupTracks.isEmpty()) return
        // The upsert updates the stored row in place, keeping its id, so it serves both cases.
        val dbTracksByTracker = novelTrackRepository.getTracksByNovelId(novelId).associateBy { it.trackerId }
        backupTracks.forEach { backupTrack ->
            val incoming = backupTrack.toTrackImpl(novelId)
            val dbTrack = dbTracksByTracker[incoming.trackerId]
            val toUpsert = if (dbTrack == null) {
                incoming
            } else {
                val lastChapterRead = backupChapterReadAhead(dbTrack.lastChapterRead, incoming.lastChapterRead)
                    ?: return@forEach
                dbTrack.copy(lastChapterRead = lastChapterRead)
            }
            check(novelTrackRepository.upsert(toUpsert)) { "Failed to upsert track ${toUpsert.trackerId}" }
        }
    }

    private suspend fun restoreHistory(
        chapters: List<NovelChapter>,
        backupHistory: List<BackupNovelHistory>,
    ) {
        val chapterIdsByUrl = chapters.associate { it.url to it.id }
        backupHistory
            .map { RestoredChapterHistory(it.url, it.lastRead.toInstantOrNull(), it.readDuration) }
            .foldHistoryCopies()
            .forEach { history ->
                val chapterId = chapterIdsByUrl[history.chapterUrl] ?: return@forEach
                novelHistoryRepository.restoreHistory(chapterId, history.readAt, history.readDuration)
            }
    }

    /**
     * Materialize the backup's novel merge groups into the merge_group tables. Each ref is resolved to
     * the restored novel's fresh id; the shared [RestoreMergeGroups] decides the rest, so both content
     * types restore grouping the same way.
     */
    suspend fun restoreMerges(
        merges: List<BackupNovelMergeGroup>,
        prefEra: PrefEraGrouping<BackupNovelSourceRef>?,
    ) {
        restoreMergeGroups.fromBackup(ContentType.NOVELS, merges.map { it.refs }, prefEra) {
            novelRepository.getByUrlAndSource(it.url, it.source)?.id
        }
    }

    /** An older root-list row for a novel the backup does not list, applied when the device has it. */
    suspend fun restoreCustomInfo(source: String, url: String, info: BackupCustomInfo) {
        novelRepository.getByUrlAndSource(url, source)?.let { restoreCustomInfo(it.id, info) }
    }

    /** The manga twin is MangaRestorer.restoreCustomInfo; both read BackupCustomInfoFields.customInfo. */
    private suspend fun restoreCustomInfo(novelId: Long, info: BackupCustomInfo) {
        setCustomNovelInfo.set(
            CustomNovelInfo(
                novelId = novelId,
                title = info.title,
                author = info.author,
                artist = info.artist,
                description = info.description,
                genre = info.genre,
                status = info.status,
                thumbnailUrl = info.thumbnailUrl,
            ),
        )
    }
}
