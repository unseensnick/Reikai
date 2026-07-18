package reikai.domain.novel.interactor

import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.hasCustomCover
import reikai.novel.download.NovelDownloadManager
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

/**
 * Move a favorited novel's state onto a [target] novel from another source, the novel twin of
 * [mihon.domain.migration.usecases.MigrateMangaUseCase] (and modelled on LNReader's `migrateNovel`).
 *
 * The target is already materialised + chapter-synced by the picker, so this is mostly DB-only: it
 * copies per-chapter read / bookmark / scroll-progress (matched by chapter number), moves categories,
 * carries the custom cover + notes when their flags are set, favorites the target, carries tracker
 * links (re-pointed to the target, like manga), and keeps any merge group consistent: the target takes
 * the source's place on [replace], or joins it on copy, for manual and same-title auto groups alike.
 * History-tab rows are intentionally not carried (parity with Mihon, which carries tracks but not
 * history).
 */
class MigrateNovelUseCase(
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val getNovelCategories: GetNovelCategories = Injekt.get(),
    private val setNovelCategories: SetNovelCategories = Injekt.get(),
    private val novelMergeManager: NovelMergeManager = Injekt.get(),
    private val novelDownloadManager: NovelDownloadManager = Injekt.get(),
    private val updateNovel: UpdateNovel = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getNovelTracks: GetNovelTracks = Injekt.get(),
    private val insertNovelTrack: InsertNovelTrack = Injekt.get(),
) {

    suspend operator fun invoke(
        current: Novel,
        target: Novel,
        flags: Set<NovelMigrationFlag>,
        replace: Boolean,
    ) {
        if (current.id == target.id) return
        try {
            // Capture the source's merge group up front, before the target is favorited, so it's the
            // source plus its existing siblings, not the target (which shares the title on a clean match).
            val group = novelMergeManager.computeRelatedNovelIds(current.id)

            // Fetch the source's chapters once when either the chapter-state carry or the
            // remove-download flag needs them.
            val currentChapters = if (
                NovelMigrationFlag.CHAPTER in flags || NovelMigrationFlag.REMOVE_DOWNLOAD in flags
            ) {
                novelChapterRepository.getByNovelId(current.id)
            } else {
                emptyList()
            }
            // Disk-download membership on the old source (from NovelDownloadCache), for both the
            // re-download carry and the remove-download delete below.
            val currentDownloadedIds = currentChapters
                .filter { novelDownloadManager.isChapterDownloaded(current, it) }
                .mapTo(HashSet()) { it.id }

            if (NovelMigrationFlag.CHAPTER in flags) {
                val targetChapters = novelChapterRepository.getByNovelId(target.id)
                computeChapterMigration(currentChapters, targetChapters).forEach {
                    novelChapterRepository.update(it)
                }
                // Re-queue downloads for the target chapters that were offline on the old source (the
                // file isn't copied, it's re-fetched, like LNReader). downloadChapters skips ones the
                // target already has.
                chaptersToRedownload(currentChapters, targetChapters, currentDownloadedIds)
                    .takeIf { it.isNotEmpty() }
                    ?.let { novelDownloadManager.downloadChapters(it) }
            }

            if (NovelMigrationFlag.CATEGORY in flags) {
                val categoryIds = getNovelCategories.awaitByNovelId(current.id).map { it.id }
                setNovelCategories.await(target.id, categoryIds)
            }

            if (NovelMigrationFlag.COVER in flags && current.hasCustomCover(coverCache)) {
                coverCache.getCustomCoverFile(-current.id).inputStream().use { input ->
                    coverCache.getCustomCoverFile(-target.id).outputStream().use { output -> input.copyTo(output) }
                }
                // Bump the target's coverLastModified so coil reloads, mirroring NovelCoverScreenModel.
                updateNovel.awaitUpdateCoverLastModified(target.id)
            }

            // Delete the old source's downloaded chapters (parity with manga's REMOVE_DOWNLOAD). The
            // file delete is a no-op when nothing is downloaded.
            if (NovelMigrationFlag.REMOVE_DOWNLOAD in flags) {
                novelDownloadManager.deleteChapters(currentChapters.filter { it.id in currentDownloadedIds })
            }

            updateNovel.await(
                NovelUpdate(
                    id = target.id,
                    favorite = true,
                    // Inherit the source's added-date on a replace, else stamp now, matching manga
                    // migration; this favorite path bypasses awaitUpdateFavorite, which is the only
                    // other place dateAdded is set, so without this a migrated novel sorts to epoch 0.
                    dateAdded = if (replace) current.dateAdded else Instant.now().toEpochMilli(),
                    // Carry the chapter-list (sort/filter/display) and reader (orientation) flags onto
                    // the target unconditionally, matching manga migration.
                    chapterFlags = current.chapterFlags,
                    viewerFlags = current.viewerFlags,
                    lastReadAt = current.lastReadAt ?: target.lastReadAt,
                    notes = if (NovelMigrationFlag.NOTES in flags) current.notes else null,
                ),
            )

            // Carry tracker links onto the target, re-pointed to its id (matching manga migration). The
            // source's own track rows are left intact, which is correct for a Copy.
            getNovelTracks.await(current.id).forEach { insertNovelTrack.await(it.copy(novelId = target.id)) }

            // Keep the merge consistent: the target takes the source's place in the group on a replace
            // (split the source out, merge the target in with the survivors), or joins it on a copy.
            if (replace) {
                updateNovel.await(NovelUpdate(id = current.id, favorite = false))
                if (group.size > 1) {
                    val survivors = novelMergeManager.removeFromGroup(group, listOf(current.id))
                    novelMergeManager.mergeNovels(survivors.toList() + target.id)
                }
            } else if (group.size > 1) {
                novelMergeManager.mergeNovels(group.toList() + target.id)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // A failed migration was previously swallowed silently; surface it in the log (matching
            // manga migration) rather than letting the whole batch fail opaquely.
            logcat(LogPriority.ERROR, e) { "Novel migration failed (${current.id} -> ${target.id})" }
        }
    }
}

/**
 * Pure core: given the source novel's chapters and the target's, return the target chapters whose
 * read / bookmark / progress should change. A target chapter takes its matched (same chapter number)
 * source chapter's state; additionally every target chapter at or below the highest read source
 * number is marked read (mirrors Mihon's `maxChapterRead` sweep, so coarser target numbering still
 * reflects how far you'd read). Unrecognized numbers (< 0) are skipped, matching the sync convention.
 */
internal fun computeChapterMigration(
    currentChapters: List<NovelChapter>,
    targetChapters: List<NovelChapter>,
): List<NovelChapter> {
    val maxReadNumber = currentChapters
        .filter { it.read && it.chapterNumber >= 0.0 }
        .maxOfOrNull { it.chapterNumber }

    return targetChapters.mapNotNull { target ->
        if (target.chapterNumber < 0.0) return@mapNotNull null

        val match = currentChapters.firstOrNull { it.chapterNumber >= 0.0 && it.chapterNumber == target.chapterNumber }
        var read = if (match != null) match.read else target.read
        val bookmark = if (match != null) match.bookmark else target.bookmark
        val progress = if (match != null) match.lastTextProgress else target.lastTextProgress
        if (maxReadNumber != null && target.chapterNumber <= maxReadNumber) read = true

        if (read == target.read && bookmark == target.bookmark && progress == target.lastTextProgress) {
            null
        } else {
            target.copy(read = read, bookmark = bookmark, lastTextProgress = progress)
        }
    }
}

/**
 * The target chapters to re-queue for download: those whose chapter number matches a chapter that was
 * downloaded on the old source. Unrecognized numbers (< 0) are skipped. The download manager itself
 * skips any the target already has, so this is safe to call with the full matched set.
 */
internal fun chaptersToRedownload(
    currentChapters: List<NovelChapter>,
    targetChapters: List<NovelChapter>,
    downloadedChapterIds: Set<Long>,
): List<NovelChapter> {
    val downloadedNumbers = currentChapters
        .filter { it.id in downloadedChapterIds && it.chapterNumber >= 0.0 }
        .mapTo(HashSet()) { it.chapterNumber }
    if (downloadedNumbers.isEmpty()) return emptyList()
    return targetChapters.filter { it.chapterNumber >= 0.0 && it.chapterNumber in downloadedNumbers }
}
