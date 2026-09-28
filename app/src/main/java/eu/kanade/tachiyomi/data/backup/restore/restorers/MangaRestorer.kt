package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupCustomInfo
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.customInfo
import exh.metadata.metadata.base.FlatMetadata
import exh.metadata.sql.models.SearchMetadata
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import reikai.domain.backup.RestoredChapterHistory
import reikai.domain.backup.RestoredChapterState
import reikai.domain.backup.backupChapterReadAhead
import reikai.domain.backup.backupDetailsWin
import reikai.domain.backup.earliestAddedAt
import reikai.domain.backup.foldBackup
import reikai.domain.backup.foldHistoryCopies
import reikai.domain.category.CategoryContentType
import reikai.domain.category.byNamePreferring
import reikai.domain.library.ContentType
import reikai.domain.merge.RestoreMergeGroups
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaMetadataRepository
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.UpsertTrack
import tachiyomi.domain.track.model.Track
import java.util.Date
import kotlin.math.max
import kotlin.time.Clock

@Inject
class MangaRestorer(
    private val database: Database,
    private val getCategories: GetCategories,
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateManga: UpdateManga,
    private val getTracks: GetTracks,
    private val upsertTrack: UpsertTrack,
    fetchInterval: FetchInterval,
    // RK: target of the restored manga merge groups (see restoreMerges).
    private val restoreMergeGroups: RestoreMergeGroups,
    // RK: restores captured adult/EXH gallery metadata (search_metadata/tags/titles).
    private val mangaMetadataRepository: MangaMetadataRepository,
    // RK: writes each restored entry's custom info under its new id.
    private val setCustomMangaInfo: SetCustomMangaInfo,
) {

    private val timeZone = TimeZone.currentSystemDefault()
    private val now = Clock.System.now().toLocalDateTime(timeZone)
    private val currentFetchWindow = fetchInterval.getWindow(now.date, timeZone)

    suspend fun restore(
        backupManga: BackupManga,
        backupCategories: List<BackupCategory>,
    ) {
        database.transaction {
            val dbManga = findExistingManga(backupManga)
            val manga = backupManga.getMangaImpl()
            val restoredManga = if (dbManga == null) {
                restoreNewManga(manga)
            } else {
                restoreExistingManga(manga, dbManga)
            }

            restoreMangaDetails(
                manga = restoredManga,
                chapters = backupManga.chapters,
                categories = backupManga.categories,
                backupCategories = backupCategories,
                history = backupManga.history,
                tracks = backupManga.tracking,
                excludedScanlators = backupManga.excludedScanlators,
                searchMetadata = backupManga.searchMetadata, // RK: adult gallery metadata
            )
            // RK: an entry without custom info leaves the device's own alone, as the forks do.
            backupManga.customInfo?.let { restoreCustomInfo(restoredManga.id, it) }
        }
    }

    private suspend fun findExistingManga(backupManga: BackupManga): Manga? {
        return getMangaByUrlAndSourceId.await(backupManga.url, backupManga.source)
    }

    private suspend fun restoreExistingManga(manga: Manga, dbManga: Manga): Manga {
        // RK: which copy's details win, and the library date, are kernels the novel restore shares
        val details = if (backupDetailsWin(dbManga.initialized, manga.initialized)) manga else dbManga
        return updateManga(
            dbManga.copy(
                favorite = dbManga.favorite || manga.favorite,
                dateAdded = earliestAddedAt(dbManga.dateAdded, manga.dateAdded), // RK
                title = details.title,
                artist = details.artist,
                author = details.author,
                description = details.description,
                genre = details.genre,
                status = details.status,
                thumbnailUrl = details.thumbnailUrl,
                updateStrategy = details.updateStrategy,
                initialized = dbManga.initialized || manga.initialized,
                // Merge both backup and local data with local winning
                memo = JsonObject(manga.memo + dbManga.memo),
            ),
        )
    }

    private suspend fun updateManga(manga: Manga): Manga {
        database.mangasQueries.update(
            source = manga.source,
            url = manga.url,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre,
            title = manga.title,
            status = manga.status,
            thumbnailUrl = manga.thumbnailUrl,
            favorite = manga.favorite,
            lastUpdate = manga.lastUpdate,
            nextUpdate = null,
            calculateInterval = null,
            initialized = manga.initialized,
            viewer = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = manga.coverLastModified,
            dateAdded = manga.dateAdded,
            mangaId = manga.id,
            updateStrategy = manga.updateStrategy,
            notes = manga.notes,
            memo = manga.memo,
        )
        return manga
    }

    private suspend fun restoreNewManga(
        manga: Manga,
    ): Manga {
        return manga.copy(
            id = insertManga(manga),
        )
    }

    private suspend fun restoreChapters(manga: Manga, backupChapters: List<BackupChapter>) {
        val dbChaptersByUrl = getChaptersByMangaId.await(manga.id)
            .associateBy { it.url }

        val (existingChapters, newChapters) = backupChapters
            .mapNotNull {
                val chapter = it.toChapterImpl().copy(mangaId = manga.id)

                val dbChapter = dbChaptersByUrl[chapter.url]
                    ?: // New chapter
                    return@mapNotNull chapter

                if (chapter.forComparison() == dbChapter.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // RK: read state folds through the kernel novels share
                val readState = RestoredChapterState(dbChapter.read, dbChapter.bookmark, dbChapter.lastPageRead)
                    .foldBackup(RestoredChapterState(chapter.read, chapter.bookmark, chapter.lastPageRead))
                chapter
                    .copyFrom(dbChapter)
                    .copy(
                        id = dbChapter.id,
                        read = readState.read, // RK
                        bookmark = readState.bookmark, // RK
                        lastPageRead = readState.progress, // RK
                        dateFetch = dbChapter.dateFetch,
                        sourceOrder = dbChapter.sourceOrder,
                        // Merge both backup and local data with local winning
                        memo = JsonObject(chapter.memo + dbChapter.memo),
                        // RK: 0 means the reader never loaded the chapter, so whichever side knows the
                        // count wins and a backup predating the column cannot erase one on the device.
                        pageCount = maxOf(chapter.pageCount, dbChapter.pageCount),
                    )
            }
            .partition { it.id > 0 }

        insertNewChapters(newChapters)
        updateExistingChapters(existingChapters)
    }

    // RK: pageCount is excluded because it is derived rather than user state. Comparing it would make
    // every restore from a backup predating the column rewrite the whole chapter list to no effect.
    private fun Chapter.forComparison() = this.copy(
        id = 0L,
        mangaId = 0L,
        dateFetch = 0L,
        dateUpload = 0L,
        pageCount = 0L,
    )

    private suspend fun insertNewChapters(chapters: List<Chapter>) {
        database.transaction {
            chapters.forEach { chapter ->
                database.chaptersQueries.insert(
                    chapter.mangaId,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    chapter.read,
                    chapter.bookmark,
                    chapter.lastPageRead,
                    chapter.chapterNumber,
                    chapter.sourceOrder,
                    chapter.dateFetch,
                    chapter.dateUpload,
                    chapter.memo,
                    chapter.pageCount, // RK: page count
                )
            }
        }
    }

    private suspend fun updateExistingChapters(chapters: List<Chapter>) {
        database.transaction {
            chapters.forEach { chapter ->
                database.chaptersQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = null,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = null,
                    sourceOrder = null,
                    dateFetch = null,
                    dateUpload = null,
                    chapterId = chapter.id,
                    memo = chapter.memo,
                    pageCount = chapter.pageCount, // RK: page count
                )
            }
        }
    }

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    private suspend fun insertManga(manga: Manga): Long {
        return database.mangasQueries.insertReturningId(
            source = manga.source,
            url = manga.url,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre,
            title = manga.title,
            status = manga.status,
            thumbnailUrl = manga.thumbnailUrl,
            favorite = manga.favorite,
            lastUpdate = manga.lastUpdate,
            nextUpdate = 0L,
            calculateInterval = 0L,
            initialized = manga.initialized,
            viewerFlags = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = manga.coverLastModified,
            dateAdded = manga.dateAdded,
            updateStrategy = manga.updateStrategy,
            notes = manga.notes,
            memo = manga.memo,
        )
            .awaitAsOne()
    }

    private suspend fun restoreMangaDetails(
        manga: Manga,
        chapters: List<BackupChapter>,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
        history: List<BackupHistory>,
        tracks: List<BackupTracking>,
        excludedScanlators: List<String>,
        searchMetadata: BackupSearchMetadata?, // RK: adult gallery metadata
    ): Manga {
        restoreCategories(manga, categories, backupCategories)
        restoreChapters(manga, chapters)
        restoreTracking(manga, tracks)
        restoreHistory(manga, history)
        restoreExcludedScanlators(manga, excludedScanlators)
        restoreSearchMetadata(manga, searchMetadata) // RK: adult gallery metadata
        updateManga.awaitUpdateFetchInterval(manga, timeZone, now, currentFetchWindow)
        return manga
    }

    // RK: re-insert captured adult/EXH gallery metadata for a restored gallery, keyed to its new id.
    private suspend fun restoreSearchMetadata(manga: Manga, backup: BackupSearchMetadata?) {
        backup ?: return
        mangaMetadataRepository.insertFlatMetadata(
            FlatMetadata(
                metadata = SearchMetadata(
                    mangaId = manga.id,
                    uploader = backup.uploader,
                    extra = backup.extra,
                    indexedExtra = backup.indexedExtra,
                    extraVersion = backup.extraVersion,
                ),
                tags = backup.tags.map {
                    SearchTag(id = null, mangaId = manga.id, namespace = it.namespace, name = it.name, type = it.type)
                },
                titles = backup.titles.map {
                    SearchTitle(id = null, mangaId = manga.id, title = it.title, type = it.type)
                },
            ),
        )
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    private suspend fun restoreCategories(
        manga: Manga,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
    ) {
        val dbCategories = getCategories.await()
        // RK: a universal row may share a manga row's name
        val dbCategoriesByName = dbCategories.byNamePreferring(CategoryContentType.MANGA)

        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val mangaCategoriesToUpdate = categories.mapNotNull { backupCategoryOrder ->
            backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                dbCategoriesByName[backupCategory.name]?.let { dbCategory ->
                    Pair(manga.id, dbCategory.id)
                }
            }
        }

        if (mangaCategoriesToUpdate.isNotEmpty()) {
            database.transaction {
                database.mangas_categoriesQueries.deleteMangaCategoryByMangaId(manga.id)
                mangaCategoriesToUpdate.forEach { (mangaId, categoryId) ->
                    database.mangas_categoriesQueries.insert(mangaId, categoryId)
                }
            }
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
        getMangaByUrlAndSourceId.await(url, source)?.let { restoreCustomInfo(it.id, info) }
    }

    // RK: the novel twin is NovelRestorer.restoreCustomInfo; both read BackupCustomInfoFields.customInfo.
    private suspend fun restoreCustomInfo(mangaId: Long, info: BackupCustomInfo) {
        setCustomMangaInfo.set(
            CustomMangaInfo(
                mangaId = mangaId,
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
    // RK <--

    // RK --> mihon 553762fae (upstream's RestoreRepositoryImpl.restoreHistory) ported ahead of the move
    // to :data; the copies fold through the kernel NovelRestorer calls too (reikai.domain.backup).
    private suspend fun restoreHistory(manga: Manga, backupHistory: List<BackupHistory>) {
        val toUpdate = backupHistory
            .map {
                val history = it.getHistoryImpl()
                RestoredChapterHistory(it.url, history.readAt?.time ?: 0L, history.readDuration)
            }
            .foldHistoryCopies()
            .mapNotNull { history ->
                val dbHistory = database.historyQueries
                    .getHistoryByChapterUrlAndMangaId(history.chapterUrl, manga.id)
                    .awaitAsOneOrNull()

                if (dbHistory == null) {
                    val chapter = database.chaptersQueries
                        .getChapterByUrlAndMangaId(history.chapterUrl, manga.id)
                        .awaitAsOneOrNull()
                        // Chapter doesn't exist; skip
                        ?: return@mapNotNull null
                    // New history entry
                    return@mapNotNull Triple(chapter._id, Date(history.readAt), history.readDuration)
                }

                // Update history entry
                Triple(
                    dbHistory.chapter_id,
                    Date(max(history.readAt, dbHistory.last_read?.time ?: 0L)),
                    max(history.readDuration, dbHistory.time_read) - dbHistory.time_read,
                )
            }

        if (toUpdate.isEmpty()) return
        database.transaction {
            toUpdate.forEach { (chapterId, readAt, readDuration) ->
                database.historyQueries.upsert(chapterId, readAt, readDuration)
            }
        }
    }
    // RK <--

    private suspend fun restoreTracking(manga: Manga, backupTracks: List<BackupTracking>) {
        val dbTrackByTrackerId = getTracks.await(manga.id).associateBy { it.trackerId }

        val (existingTracks, newTracks) = backupTracks
            .mapNotNull {
                val track = it.getTrackImpl()
                val dbTrack = dbTrackByTrackerId[track.trackerId]
                    ?: // New track
                    return@mapNotNull track.copy(
                        id = 0, // Let DB assign new ID
                        mangaId = manga.id,
                    )

                // RK --> the rule novels share (reikai.domain.backup.backupChapterReadAhead), upstream's
                // mihon 4b48a84ec ported ahead of the move to RestoreRepositoryImpl.
                val lastChapterRead = backupChapterReadAhead(dbTrack.lastChapterRead, track.lastChapterRead)
                    ?: return@mapNotNull null

                // Update to an existing track
                dbTrack.copy(lastChapterRead = lastChapterRead)
                // RK <--
            }
            .partition { it.id > 0 }

        if (newTracks.isNotEmpty()) {
            upsertTrack.awaitAll(newTracks)
        }

        if (existingTracks.isEmpty()) return
        database.transaction {
            existingTracks.forEach { track ->
                database.manga_syncQueries.update(
                    track.mangaId,
                    track.trackerId,
                    track.remoteId,
                    track.libraryId,
                    track.title,
                    track.lastChapterRead,
                    track.totalChapters,
                    track.status,
                    track.score,
                    track.remoteUrl,
                    track.startDate,
                    track.finishDate,
                    track.private,
                    track.id,
                )
            }
        }
    }

    /**
     * Restores the excluded scanlators for the manga.
     *
     * @param manga the manga whose excluded scanlators have to be restored.
     * @param excludedScanlators the excluded scanlators to restore.
     */
    private suspend fun restoreExcludedScanlators(manga: Manga, excludedScanlators: List<String>) {
        if (excludedScanlators.isEmpty()) return
        val existingExcludedScanlators = database.excluded_scanlatorsQueries
            .getExcludedScanlatorsByMangaId(manga.id)
            .awaitAsList()
        val toInsert = excludedScanlators.filter { it !in existingExcludedScanlators }
        if (toInsert.isEmpty()) return
        toInsert.forEach { database.excluded_scanlatorsQueries.insert(manga.id, it) }
    }
}
