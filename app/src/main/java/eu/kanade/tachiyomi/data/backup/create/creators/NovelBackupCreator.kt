// RK: novel backup (Roadmap 9). Net-new Reikai file: the light-novel twin of MangaBackupCreator,
// plus the novel categories and the merge/unmerge groups (the latter re-keyed from preference IDs to
// stable {url, source} refs so they survive restore). Gated by the same BackupOptions toggles as
// manga (novels are first-class library content, no separate UI toggle).
package eu.kanade.tachiyomi.data.backup.create.creators

import app.cash.sqldelight.async.coroutines.awaitAsList
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupCustomNovelInfo
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelHistory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSourceRef
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelCategoryRepository
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.repository.CustomNovelInfoRepository
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelBackupCreator(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get(),
    private val novelTrackRepository: NovelTrackRepository = Injekt.get(),
    private val preferences: ReikaiLibraryPreferences = Injekt.get(),
    private val customNovelInfoRepository: CustomNovelInfoRepository = Injekt.get(),
    private val database: Database = Injekt.get(),
) {

    data class NovelBackupData(
        val novels: List<BackupNovel>,
        val categories: List<BackupNovelCategory>,
        val merges: List<BackupNovelMergeGroup>,
        val unmerges: List<BackupNovelMergeGroup>,
        val customInfo: List<BackupCustomNovelInfo>,
    )

    suspend operator fun invoke(options: BackupOptions): NovelBackupData {
        val favorites = if (options.libraryEntries) novelRepository.getFavorites() else emptyList()
        return NovelBackupData(
            novels = if (options.libraryEntries) favorites.map { backupNovel(it, options) } else emptyList(),
            categories = if (options.categories) backupNovelCategories() else emptyList(),
            customInfo = if (options.libraryEntries) backupCustomNovelInfo(favorites) else emptyList(),
            merges = if (options.libraryEntries) {
                serializeGroups(preferences.novelManualMerges.get(), favorites)
            } else {
                emptyList()
            },
            unmerges = if (options.libraryEntries) {
                serializeGroups(preferences.novelManualUnmerges.get(), favorites)
            } else {
                emptyList()
            },
        )
    }

    // Back up the novel custom-info overlay as {url, source}-keyed entries (re-keyed to fresh ids on
    // restore). The favorites map resolves each row's novel; a row for a non-favorite is dropped.
    private suspend fun backupCustomNovelInfo(favorites: List<Novel>): List<BackupCustomNovelInfo> {
        val byId = favorites.associateBy { it.id }
        return customNovelInfoRepository.getAll().mapNotNull { info ->
            val novel = byId[info.novelId] ?: return@mapNotNull null
            BackupCustomNovelInfo(
                source = novel.source,
                url = novel.url,
                title = info.title,
                author = info.author,
                artist = info.artist,
                description = info.description,
                genre = info.genre.orEmpty(),
                status = info.status,
                thumbnailUrl = info.thumbnailUrl,
            )
        }
    }

    private suspend fun backupNovel(novel: Novel, options: BackupOptions): BackupNovel {
        val novelObject = novel.toBackupNovel()

        if (options.chapters) {
            novelChapterRepository.getByNovelId(novel.id)
                .map { it.toBackupNovelChapter() }
                .takeUnless(List<BackupNovelChapter>::isEmpty)
                ?.let { novelObject.chapters = it }
        }

        if (options.categories) {
            val categoriesForNovel = novelCategoryRepository.getAllByNovelId(novel.id)
            if (categoriesForNovel.isNotEmpty()) {
                novelObject.categories = categoriesForNovel.map { it.order }
            }
        }

        if (options.tracking) {
            val tracks = novelTrackRepository.getTracksByNovelId(novel.id).map { it.toBackupNovelTracking() }
            if (tracks.isNotEmpty()) {
                novelObject.tracking = tracks
            }
        }

        if (options.history) {
            val history = database.novel_historyQueries
                .getByNovelId(novel.id) { url, lastRead, timeRead ->
                    BackupNovelHistory(url = url, lastRead = lastRead ?: 0L, readDuration = timeRead)
                }
                .awaitAsList()
            if (history.isNotEmpty()) {
                novelObject.history = history
            }
        }

        return novelObject
    }

    private suspend fun backupNovelCategories(): List<BackupNovelCategory> {
        return novelCategoryRepository.getAll().map {
            BackupNovelCategory(name = it.name, order = it.order, id = it.id, flags = it.flags)
        }
    }

    /**
     * Translate the preference's comma-joined ID groups into {url, source} refs. A group is dropped
     * if fewer than two of its members resolve (a one-member group is no longer a merge).
     */
    private suspend fun serializeGroups(groups: Set<String>, favorites: List<Novel>): List<BackupNovelMergeGroup> {
        if (groups.isEmpty()) return emptyList()
        val byId = favorites.associateBy { it.id }
        return groups.mapNotNull { group ->
            val refs = group.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .mapNotNull { id ->
                    val novel = byId[id] ?: novelRepository.getById(id)
                    novel?.let { BackupNovelSourceRef(url = it.url, source = it.source) }
                }
            refs.takeIf { it.size >= 2 }?.let { BackupNovelMergeGroup(refs = it) }
        }
    }
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
    dateAdded = this.dateAdded,
    lastUpdate = this.lastUpdate,
    initialized = this.initialized,
    chapterFlags = this.chapterFlags,
    updateStrategy = this.updateStrategy,
    coverLastModified = this.coverLastModified,
    totalPages = this.totalPages,
    lastReadAt = this.lastReadAt,
    favorite = this.favorite,
    notes = this.notes,
    viewerFlags = this.viewerFlags,
    version = this.version,
)

private fun NovelChapter.toBackupNovelChapter() = BackupNovelChapter(
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
