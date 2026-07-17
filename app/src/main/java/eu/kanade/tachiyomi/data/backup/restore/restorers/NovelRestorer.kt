// RK: novel backup (Roadmap 9). Net-new Reikai file: the light-novel twin of MangaRestorer. Matches
// or inserts each novel by url+source, re-links its chapters (by url), categories (by name), tracks
// (by tracker id) and history (chapter url -> new chapter id), then materializes the merge groups into
// the merge_group tables from the backup's {url, source} refs once every novel has its fresh id.
package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCustomNovelInfo
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelHistory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.novel.NovelCategoryRepository
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.interactor.SetCustomNovelInfo
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelCategory
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max

class NovelRestorer(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get(),
    private val novelTrackRepository: NovelTrackRepository = Injekt.get(),
    private val mergeGroupRepository: MergeGroupRepository = Injekt.get(),
    private val setCustomNovelInfo: SetCustomNovelInfo = Injekt.get(),
    private val database: Database = Injekt.get(),
) {

    /** Create any novel categories the backup has that the device doesn't, matched by name. */
    suspend fun restoreCategories(backupCategories: List<BackupNovelCategory>) {
        if (backupCategories.isEmpty()) return
        val dbCategories = novelCategoryRepository.getAll()
        val dbCategoryNames = dbCategories.mapTo(HashSet()) { it.name }
        var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0L

        backupCategories
            .sortedBy { it.order }
            .forEach { backupCategory ->
                if (backupCategory.name in dbCategoryNames) return@forEach
                novelCategoryRepository.insert(
                    NovelCategory(
                        id = 0L,
                        name = backupCategory.name,
                        order = nextOrder++,
                        flags = backupCategory.flags,
                        novelOrder = "",
                    ),
                )
            }
    }

    suspend fun restore(backupNovel: BackupNovel, backupCategories: List<BackupNovelCategory>) {
        val dbNovel = novelRepository.getByUrlAndSource(backupNovel.url, backupNovel.source)
        val novel = backupNovel.toNovelImpl()

        val novelId = if (dbNovel == null) {
            novelRepository.insert(novel) ?: return
        } else {
            // Keep the newer copy (higher version), the novel twin of MangaRestorer: take details from
            // whichever side has the larger edit count, preserve the other's local fields. isSyncing =
            // true so the restore write itself does not inflate the version via the DB trigger.
            val merged = if (novel.version > dbNovel.version) {
                dbNovel.copyFrom(novel)
            } else {
                novel.copyFrom(dbNovel)
            }
            novelRepository.update(merged.copy(id = dbNovel.id), isSyncing = true)
            dbNovel.id
        }

        restoreChapters(novelId, backupNovel.chapters)
        restoreCategoryMembership(novelId, backupNovel.categories, backupCategories)
        restoreTracks(novelId, backupNovel.tracking)
        restoreHistory(novelId, backupNovel.history)
    }

    /**
     * Fold the newer copy's source details (and edit-count) onto this base, preserving the base's
     * local fields. User edits are no longer in the row (they live in the custom_novel_info overlay,
     * restored separately), so only source-owned details travel here.
     */
    private fun Novel.copyFrom(newer: Novel): Novel = this.copy(
        favorite = this.favorite || newer.favorite,
        author = newer.author,
        artist = newer.artist,
        description = newer.description,
        genre = newer.genre,
        thumbnailUrl = newer.thumbnailUrl,
        status = newer.status,
        initialized = this.initialized || newer.initialized,
        version = newer.version,
    )

    private suspend fun restoreChapters(
        novelId: Long,
        backupChapters: List<BackupNovelChapter>,
    ) {
        val dbChaptersByUrl = novelChapterRepository.getByNovelId(novelId).associateBy { it.url }
        backupChapters.forEach { backupChapter ->
            val incoming = backupChapter.toChapterImpl(novelId)
            val dbChapter = dbChaptersByUrl[backupChapter.url]
            if (dbChapter == null) {
                novelChapterRepository.insert(incoming)
            } else {
                // Keep the device's structural fields; only fold in read state from the backup.
                val merged = dbChapter.copy(
                    read = dbChapter.read || incoming.read,
                    bookmark = dbChapter.bookmark || incoming.bookmark,
                    lastTextProgress = max(dbChapter.lastTextProgress, incoming.lastTextProgress),
                )
                if (merged != dbChapter) novelChapterRepository.update(merged)
            }
        }
    }

    private suspend fun restoreCategoryMembership(
        novelId: Long,
        categoryOrders: List<Long>,
        backupCategories: List<BackupNovelCategory>,
    ) {
        if (categoryOrders.isEmpty()) return
        val dbCategoriesByName = novelCategoryRepository.getAll().associateBy { it.name }
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
        // novel_tracks is UNIQUE(novel_id, sync_id) ON CONFLICT REPLACE, so insert doubles as update.
        val dbTracksByTracker = novelTrackRepository.getTracksByNovelId(novelId).associateBy { it.trackerId }
        backupTracks.forEach { backupTrack ->
            val incoming = backupTrack.toTrackImpl(novelId)
            val dbTrack = dbTracksByTracker[incoming.trackerId]
            val toInsert = if (dbTrack == null) {
                incoming
            } else {
                incoming.copy(lastChapterRead = max(dbTrack.lastChapterRead, incoming.lastChapterRead))
            }
            novelTrackRepository.insert(toInsert)
        }
    }

    private suspend fun restoreHistory(
        novelId: Long,
        backupHistory: List<BackupNovelHistory>,
    ) {
        backupHistory.forEach { history ->
            val chapter = novelChapterRepository.getByUrlAndNovelId(history.url, novelId) ?: return@forEach
            database.novel_historyQueries.restoreUpsert(chapter.id, history.lastRead, history.readDuration)
        }
    }

    /**
     * Materialize the backup's novel merge groups into the merge_group tables. Each ref is resolved to
     * the restored novel's fresh id; a group with fewer than two resolved members is skipped. Additive:
     * each backup group is merged in via the repository (which absorbs any overlapping local group);
     * local-only groups are left alone.
     */
    suspend fun restoreMerges(merges: List<BackupNovelMergeGroup>) {
        merges.forEach { group ->
            val ids = group.refs
                .mapNotNull { novelRepository.getByUrlAndSource(it.url, it.source)?.id }
                .distinct()
            if (ids.size >= 2) {
                mergeGroupRepository.merge(ContentType.NOVELS, ids)
            }
        }
    }

    /** Apply the backup's novel custom-info overlay, re-keyed from {url,source} to the restored ids. */
    suspend fun restoreCustomNovelInfo(entries: List<BackupCustomNovelInfo>) {
        if (entries.isEmpty()) return
        entries.forEach { entry ->
            val novelId = novelRepository.getByUrlAndSource(entry.url, entry.source)?.id ?: return@forEach
            setCustomNovelInfo.set(
                CustomNovelInfo(
                    novelId = novelId,
                    title = entry.title,
                    author = entry.author,
                    artist = entry.artist,
                    description = entry.description,
                    genre = entry.genre.ifEmpty { null },
                    status = entry.status,
                    thumbnailUrl = entry.thumbnailUrl,
                ),
            )
        }
    }
}
