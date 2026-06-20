// RK: novel backup (ROADMAP #9). Net-new Reikai file: the light-novel twin of MangaRestorer. Matches
// or inserts each novel by url+source, re-links its chapters (by url), categories (by name), tracks
// (by tracker id) and history (chapter url -> new chapter id), then rebuilds the merge/unmerge prefs
// from the backup's {url, source} refs once every novel has its fresh id.
package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelHistory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelCategoryRepository
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.NovelTrackRepository
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
    private val preferences: ReikaiLibraryPreferences = Injekt.get(),
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
            novelRepository.update(
                novel.copy(
                    id = dbNovel.id,
                    favorite = dbNovel.favorite || novel.favorite,
                    initialized = dbNovel.initialized || novel.initialized,
                ),
            )
            dbNovel.id
        }

        restoreChapters(novelId, backupNovel.chapters)
        restoreCategoryMembership(novelId, backupNovel.categories, backupCategories)
        restoreTracks(novelId, backupNovel.tracking)
        restoreHistory(novelId, backupNovel.history)
    }

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
     * Rebuild the merge/unmerge preferences from the backup's stable refs. Each ref is resolved to the
     * restored novel's fresh id; a group is dropped if fewer than two members resolve. Unions with any
     * existing entries so a partial restore doesn't wipe device-local merges.
     */
    suspend fun restoreMerges(merges: List<BackupNovelMergeGroup>, unmerges: List<BackupNovelMergeGroup>) {
        val resolvedMerges = resolveGroups(merges)
        val resolvedUnmerges = resolveGroups(unmerges)
        if (resolvedMerges.isNotEmpty()) {
            preferences.novelManualMerges.set(preferences.novelManualMerges.get() + resolvedMerges)
        }
        if (resolvedUnmerges.isNotEmpty()) {
            preferences.novelManualUnmerges.set(preferences.novelManualUnmerges.get() + resolvedUnmerges)
        }
    }

    private suspend fun resolveGroups(groups: List<BackupNovelMergeGroup>): Set<String> {
        if (groups.isEmpty()) return emptySet()
        return groups.mapNotNull { group ->
            val ids = group.refs
                .mapNotNull { novelRepository.getByUrlAndSource(it.url, it.source)?.id }
                .distinct()
                .sorted()
            ids.takeIf { it.size >= 2 }?.joinToString(",")
        }.toSet()
    }
}
