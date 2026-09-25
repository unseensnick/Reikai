// RK: novel backup. Net-new Reikai file: the light-novel twin of MangaRestorer. Matches
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
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import eu.kanade.tachiyomi.data.backup.models.customInfo
import reikai.data.backup.RestoredChapterState
import reikai.data.backup.RestoredTrackLink
import reikai.data.backup.foldBackup
import reikai.data.novel.updateNovelFetchInterval
import reikai.domain.category.CategoryContentType
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.category.backupCategoryIdToName
import reikai.domain.category.translateCategoryId
import reikai.domain.category.translateCategoryIds
import reikai.domain.library.ContentType
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.interactor.SetCustomNovelInfo
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import tachiyomi.data.Database
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

@Inject
class NovelRestorer(
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val novelTrackRepository: NovelTrackRepository,
    private val restoreMergeGroups: RestoreMergeGroups,
    private val setCustomNovelInfo: SetCustomNovelInfo,
    private val database: Database,
    private val categoryIdPreferences: CategoryIdPreferences,
) {

    /** Create any novel categories the backup has that the device doesn't, matched by name. */
    suspend fun restoreCategories(backupCategories: List<BackupNovelCategory>) {
        if (backupCategories.isEmpty()) return
        val dbCategories = categoryRepository.getAll(CategoryContentType.NOVEL)
        val dbCategoryNames = dbCategories.mapTo(HashSet()) { it.name }
        var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0L

        backupCategories
            .sortedBy { it.order }
            .forEach { backupCategory ->
                if (backupCategory.name in dbCategoryNames) return@forEach
                categoryRepository.insert(
                    Category(
                        id = 0L,
                        name = backupCategory.name,
                        order = nextOrder++,
                        flags = backupCategory.flags,
                    ),
                    CategoryContentType.NOVEL,
                )
            }
    }

    /**
     * Remap the novel category-id preferences to the freshly restored local ids, matched by name. The
     * manga equivalent runs inline in PreferenceRestorer, but novel categories are not restored until after
     * app preferences, so the novel prefs still name the backup's ids until this runs. Mirrors the manga
     * behavior: a stored id survives only if a restored category kept its name; the default's 0 (uncategorized)
     * and -1 (prompt) sentinels are left alone. Call once, after [restoreCategories].
     */
    suspend fun remapCategoryPreferences(backupCategories: List<BackupNovelCategory>) {
        if (backupCategories.isEmpty()) return
        val backupIdToName = backupCategoryIdToName(backupCategories.map { it.id to it.name })
        val novelVisible = categoryRepository.getAll(CategoryContentType.NOVEL)
        // On a name shared by a novel-typed and a universal row, bind to the novel-typed one (the
        // same rule CategoriesRestorer applies); associateBy silently kept whichever came last.
        val nameToNewId = novelVisible.groupBy { it.name }.mapValues { (_, rows) ->
            (rows.firstOrNull { it.contentType == CategoryContentType.NOVEL } ?: rows.first()).id.toString()
        }
        // Live local ids survive untranslated: a pref key absent from the backup kept its on-device
        // value, and running that through backup-id translation dropped or remapped valid ids.
        val currentIds = novelVisible.mapTo(mutableSetOf()) { it.id.toString() }

        categoryIdPreferences.novelSets.forEach { preference ->
            val current = preference.get()
            if (current.isNotEmpty()) {
                preference.set(translateCategoryIds(current, backupIdToName, nameToNewId, currentIds))
            }
        }

        val defaultPreference = categoryIdPreferences.novelDefault
        val currentDefault = defaultPreference.get()
        if (currentDefault > 0) {
            // An id no restored category took is dropped rather than left naming whatever has that id here.
            translateCategoryId(currentDefault.toString(), backupIdToName, nameToNewId, currentIds)
                ?.toIntOrNull()
                ?.let(defaultPreference::set)
                ?: defaultPreference.delete()
        }
    }

    suspend fun restore(backupNovel: BackupNovel, backupCategories: List<BackupNovelCategory>) {
        val dbNovel = novelRepository.getByUrlAndSource(backupNovel.url, backupNovel.source)
        val novel = backupNovel.toNovelImpl()

        val novelId = if (dbNovel == null) {
            checkNotNull(novelRepository.insert(novel)) { "Failed to insert novel ${novel.url}" }
        } else {
            // Keep the newer copy (higher version), the novel twin of MangaRestorer: take details from
            // whichever side has the larger edit count, preserve the other's local fields. isSyncing =
            // true so the restore write itself does not inflate the version via the DB trigger.
            val merged = if (novel.version > dbNovel.version) {
                dbNovel.copyFrom(novel)
            } else {
                novel.copyFrom(dbNovel)
            }
            check(novelRepository.update(merged.copy(id = dbNovel.id), isSyncing = true)) {
                "Failed to update novel ${novel.url}"
            }
            dbNovel.id
        }

        restoreChapters(novelId, backupNovel.chapters)
        // Predicted from the restored chapters rather than carried in the backup, as manga's restore does.
        novelRepository.getById(novelId)?.let { updateNovelFetchInterval(it, novelChapterRepository, novelRepository) }
        restoreCategoryMembership(novelId, backupNovel.categories, backupCategories)
        restoreTracks(novelId, backupNovel.tracking)
        restoreHistory(novelId, backupNovel.history)
        // An entry without custom info leaves the device's own alone, as manga does.
        backupNovel.customInfo?.let { restoreCustomInfo(novelId, it) }
    }

    /**
     * Fold the newer copy's source details (and edit-count) onto this base, preserving the base's
     * local fields. User edits are not in the row (they live in the custom_novel_info overlay, restored
     * from the entry's custom fields), so only source-owned details travel here.
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
                checkNotNull(novelChapterRepository.insert(incoming)) { "Failed to insert chapter ${incoming.url}" }
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
    }

    private suspend fun restoreCategoryMembership(
        novelId: Long,
        categoryOrders: List<Long>,
        backupCategories: List<BackupNovelCategory>,
    ) {
        if (categoryOrders.isEmpty()) return
        // Prefer the novel-typed row when a universal one shares its name (see remap above).
        val dbCategoriesByName = categoryRepository.getAll(CategoryContentType.NOVEL)
            .groupBy { it.name }
            .mapValues { (_, rows) -> rows.firstOrNull { it.contentType == CategoryContentType.NOVEL } ?: rows.first() }
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
                val link = RestoredTrackLink(dbTrack.remoteId, dbTrack.libraryId, dbTrack.lastChapterRead)
                    .foldBackup(RestoredTrackLink(incoming.remoteId, incoming.libraryId, incoming.lastChapterRead))
                dbTrack.copy(
                    remoteId = link.remoteId,
                    libraryId = link.libraryId,
                    lastChapterRead = link.lastChapterRead,
                )
            }
            if (toInsert == dbTrack) return@forEach
            check(novelTrackRepository.insert(toInsert)) { "Failed to insert track ${toInsert.trackerId}" }
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
     * the restored novel's fresh id; the shared [RestoreMergeGroups] decides the rest, so both content
     * types restore grouping the same way.
     */
    suspend fun restoreMerges(merges: List<BackupNovelMergeGroup>) {
        restoreMergeGroups(
            ContentType.NOVELS,
            merges.map { group ->
                group.refs.mapNotNull { novelRepository.getByUrlAndSource(it.url, it.source)?.id }
            },
        )
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
