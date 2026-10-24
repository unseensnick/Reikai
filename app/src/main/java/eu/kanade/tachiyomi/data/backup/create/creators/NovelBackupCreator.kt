// Novel backup. Net-new Reikai file: the light-novel twin of MangaBackupCreator, pinned by
// backupEntries (BackupEntryDriverTest, GroupMemberBackupConformanceTest), the driver both run through,
// plus the novel categories and the merge groups (serialized from the merge_group tables as stable
// {url, source} refs so they survive restore). Gated by the same BackupOptions toggles as manga
// (novels are first-class library content, no separate UI toggle).
package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelHistory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import eu.kanade.tachiyomi.data.backup.models.customInfo
import eu.kanade.tachiyomi.data.backup.models.toBackupCustomInfo
import eu.kanade.tachiyomi.data.backup.models.toBackupReadingSession
import kotlinx.coroutines.flow.first
import mihon.core.common.extensions.toEpochMillisOrZero
import reikai.data.backup.BackupEntryParts
import reikai.data.backup.mergeGroupRefs
import reikai.data.backup.writeRows
import reikai.domain.category.CategoryContentType
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.repository.CustomNovelInfoRepository
import reikai.domain.readinglog.ReadingLogRepository
import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.category.repository.CategoryRepository

@Inject
class NovelBackupCreator(
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val novelTrackRepository: NovelTrackRepository,
    private val mergeGroupRepository: MergeGroupRepository,
    private val customNovelInfoRepository: CustomNovelInfoRepository,
    private val novelHistoryRepository: NovelHistoryRepository,
    private val novelSourceManager: NovelSourceManager,
    private val chapterNumberOverrides: ChapterNumberOverrideRepository,
    private val readingLog: ReadingLogRepository,
) : BackupEntryParts<Novel, BackupNovel> {

    suspend fun novelCategories(options: BackupOptions): List<BackupNovelCategory> =
        if (options.categories) backupNovelCategories() else emptyList()

    suspend fun novelMerges(options: BackupOptions): List<BackupNovelMergeGroup> =
        if (options.libraryEntries) serializeGroups() else emptyList()

    /** Each source's name, as manga's source list carries it, so a restore can name one not installed. */
    suspend fun sources(sourceIds: Set<String>): List<BackupNovelSource> =
        sourceIds.map { BackupNovelSource(name = novelSourceManager.nameOf(it), sourceId = it) }

    // The option gates, the choice of which novels to back up and the chunking live in the driver shared
    // with manga (reikai.data.backup.backupEntries), so this class answers one part at a time for a whole
    // chunk.
    override suspend fun favorites(): List<Novel> = novelRepository.getFavorites()

    override suspend fun readNotInLibrary(): List<Novel> = novelRepository.getReadNovelsNotInLibrary()

    override suspend fun groupMembersOutsideLibrary(): List<Novel> =
        mergeGroupRepository.getAllMemberships(ContentType.NOVELS).keys
            .mapNotNull { novelRepository.getById(it) }
            .filterNot { it.favorite }

    override suspend fun base(entries: List<Novel>): List<BackupNovel> = entries.map { it.toBackupNovel() }

    override suspend fun chapters(batch: List<Pair<Novel, BackupNovel>>) {
        val novelIds = batch.map { it.first.id }
        val overridesByNovelId = chapterNumberOverrides.getByOwners(ContentType.NOVELS, novelIds)
        batch.writeRows(novelChapterRepository.getByNovelIds(novelIds), Novel::id) { entry, backup, chapters ->
            val overrides = overridesByNovelId[entry.id].orEmpty()
            backup.chapters = chapters.map { it.toBackupNovelChapter(sourceNumber = overrides[it.url]?.sourceNumber) }
        }
    }

    override suspend fun categories(batch: List<Pair<Novel, BackupNovel>>) {
        val categoriesByNovelId = categoryRepository.getCategoriesByNovelIds(batch.map { it.first.id })
        batch.writeRows(categoriesByNovelId, Novel::id) { _, backup, categories ->
            backup.categories = categories.map { it.order }
        }
    }

    override suspend fun tracking(batch: List<Pair<Novel, BackupNovel>>) {
        val tracksByNovelId = novelTrackRepository.getTracksByNovelIds(batch.map { it.first.id })
        batch.writeRows(tracksByNovelId, Novel::id) { _, backup, tracks ->
            backup.tracking = tracks.map { it.toBackupNovelTracking() }
        }
    }

    override suspend fun history(batch: List<Pair<Novel, BackupNovel>>) {
        val novelIds = batch.map { it.first.id }
        val historyByNovelId = novelHistoryRepository.getUrlKeyedHistoryByNovelIds(novelIds)
        val sessionsByNovelId = readingLog.sessionsByUrl(ContentType.NOVELS, novelIds)
        batch.forEach { (entry, backup) ->
            val history = historyByNovelId[entry.id].orEmpty().map {
                BackupNovelHistory(
                    url = it.chapterUrl,
                    lastRead = it.readAt.toEpochMillisOrZero(),
                    readDuration = it.readDuration,
                )
            }
            if (history.isNotEmpty()) {
                backup.history = history
            }
            // Sessions ride the history option, keyed by url as history is, as for manga.
            backup.readingSessions = sessionsByNovelId[entry.id].orEmpty().map { it.toBackupReadingSession() }
        }
    }

    override suspend fun customInfo(batch: List<Pair<Novel, BackupNovel>>) {
        batch.forEach { (entry, backup) ->
            customNovelInfoRepository.getByNovelIdAsFlow(entry.id).first()
                ?.let { backup.customInfo = it.toBackupCustomInfo() }
        }
    }

    private suspend fun backupNovelCategories(): List<BackupNovelCategory> {
        // Skip the shared universal row 0 (the uncategorized sentinel), like the manga category backup.
        return categoryRepository.getAll(CategoryContentType.NOVEL)
            .filterNot { it.isSystemCategory }
            .map {
                BackupNovelCategory(name = it.name, order = it.order, id = it.id, flags = it.flags)
            }
    }

    /** The persisted novel merge groups as {url, source} refs, read from the merge_group tables. */
    private suspend fun serializeGroups(): List<BackupNovelMergeGroup> =
        mergeGroupRefs(mergeGroupRepository.getAllMemberships(ContentType.NOVELS)) { id ->
            novelRepository.getById(id)?.let { BackupNovelSourceRef(url = it.url, source = it.source) }
        }.map { BackupNovelMergeGroup(refs = it) }
}

private fun Novel.toBackupNovel() = BackupNovel(
    source = this.source,
    url = this.url,
    title = this.title,
    artist = this.artist,
    author = this.author,
    description = this.description,
    genre = this.genre.orEmpty(),
    status = this.status,
    thumbnailUrl = this.thumbnailUrl,
    dateAdded = this.favoriteAt.toEpochMillisOrZero(),
    lastUpdate = this.lastUpdate.toEpochMillisOrZero(),
    initialized = this.initialized,
    chapterFlags = this.chapterFlags,
    updateStrategy = this.updateStrategy,
    coverLastModified = this.coverLastModified.toEpochMillisOrZero(),
    totalPages = this.totalPages,
    favorite = this.favorite,
    notes = this.notes,
    viewerFlags = this.viewerFlags,
    nextUpdate = this.nextUpdate.toEpochMillisOrZero(),
)

private fun NovelChapter.toBackupNovelChapter(sourceNumber: Double?) = BackupNovelChapter(
    url = this.url,
    name = this.name,
    read = this.read,
    bookmark = this.bookmark,
    lastTextProgress = this.lastTextProgress,
    chapterNumber = this.chapterNumber,
    sourceOrder = this.sourceOrder,
    dateFetch = this.dateFetch.toEpochMilliseconds(),
    dateUpload = this.dateUpload.toEpochMillisOrZero(),
    page = this.page,
    scanlator = this.scanlator,
    sourceChapterNumber = sourceNumber,
)

private fun NovelTrack.toBackupNovelTracking() = BackupNovelTracking(
    trackerId = this.trackerId,
    remoteId = this.remoteId,
    libraryId = this.libraryId,
    title = this.title,
    lastChapterRead = this.lastChapterRead,
    totalChapters = this.totalChapters,
    status = this.status,
    score = this.score,
    remoteUrl = this.remoteUrl,
    startDate = this.startDate.toEpochMillisOrZero(),
    finishDate = this.finishDate.toEpochMillisOrZero(),
    private = this.private,
)
