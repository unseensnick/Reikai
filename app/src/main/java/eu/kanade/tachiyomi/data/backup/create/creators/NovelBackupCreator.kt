// Novel backup. Net-new Reikai file: the light-novel twin of MangaBackupCreator,
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
import kotlinx.coroutines.flow.first
import reikai.data.backup.BackupEntryParts
import reikai.data.backup.mergeGroupRefs
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
) : BackupEntryParts<Novel, BackupNovel> {

    suspend fun novelCategories(options: BackupOptions): List<BackupNovelCategory> =
        if (options.categories) backupNovelCategories() else emptyList()

    suspend fun novelMerges(options: BackupOptions): List<BackupNovelMergeGroup> =
        if (options.libraryEntries) serializeGroups() else emptyList()

    /** Each source's name, as manga's source list carries it, so a restore can name one not installed. */
    suspend fun sources(sourceIds: Set<String>): List<BackupNovelSource> =
        sourceIds.map { BackupNovelSource(name = novelSourceManager.nameOf(it), sourceId = it) }

    // The option gates and the choice of which novels to back up live in the driver shared with manga
    // (reikai.data.backup.backupEntries), so this class answers one part at a time.
    override suspend fun favorites(): List<Novel> = novelRepository.getFavorites()

    override suspend fun readNotInLibrary(): List<Novel> = novelRepository.getReadNovelsNotInLibrary()

    override suspend fun groupMembersOutsideLibrary(): List<Novel> =
        mergeGroupRepository.getAllMemberships(ContentType.NOVELS).keys
            .mapNotNull { novelRepository.getById(it) }
            .filterNot { it.favorite }

    override suspend fun base(entry: Novel): BackupNovel = entry.toBackupNovel()

    override suspend fun chapters(entry: Novel, backup: BackupNovel) {
        val overrides = chapterNumberOverrides.getByOwner(ContentType.NOVELS, entry.id)
        novelChapterRepository.getByNovelId(entry.id)
            .map { it.toBackupNovelChapter(sourceNumber = overrides[it.url]?.sourceNumber) }
            .takeUnless(List<BackupNovelChapter>::isEmpty)
            ?.let { backup.chapters = it }
    }

    override suspend fun categories(entry: Novel, backup: BackupNovel) {
        val categoriesForNovel = categoryRepository.getCategoriesByNovelId(entry.id)
        if (categoriesForNovel.isNotEmpty()) {
            backup.categories = categoriesForNovel.map { it.order }
        }
    }

    override suspend fun tracking(entry: Novel, backup: BackupNovel) {
        val tracks = novelTrackRepository.getTracksByNovelId(entry.id).map { it.toBackupNovelTracking() }
        if (tracks.isNotEmpty()) {
            backup.tracking = tracks
        }
    }

    override suspend fun history(entry: Novel, backup: BackupNovel) {
        val history = novelHistoryRepository.getUrlKeyedHistoryByNovelId(entry.id).map {
            BackupNovelHistory(url = it.chapterUrl, lastRead = it.readAt, readDuration = it.readDuration)
        }
        if (history.isNotEmpty()) {
            backup.history = history
        }
    }

    override suspend fun customInfo(entry: Novel, backup: BackupNovel) {
        customNovelInfoRepository.getByNovelIdAsFlow(entry.id).first()
            ?.let { backup.customInfo = it.toBackupCustomInfo() }
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
    dateAdded = this.favoriteAt ?: 0L,
    lastUpdate = this.lastUpdate,
    initialized = this.initialized,
    chapterFlags = this.chapterFlags,
    updateStrategy = this.updateStrategy,
    coverLastModified = this.coverLastModified,
    totalPages = this.totalPages,
    favorite = this.favorite,
    notes = this.notes,
    viewerFlags = this.viewerFlags,
)

private fun NovelChapter.toBackupNovelChapter(sourceNumber: Double?) = BackupNovelChapter(
    url = this.url,
    name = this.name,
    read = this.read,
    bookmark = this.bookmark,
    lastTextProgress = this.lastTextProgress,
    chapterNumber = this.chapterNumber,
    sourceOrder = this.sourceOrder,
    dateFetch = this.dateFetch,
    dateUpload = this.dateUpload,
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
    startDate = this.startDate,
    finishDate = this.finishDate,
    private = this.private,
)
