package reikai.data.dedupe

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.DownloadStore
import logcat.LogPriority
import reikai.domain.dedupe.MergedDuplicate
import reikai.domain.dedupe.MergedDuplicateChapter
import reikai.domain.dedupe.survivorIds
import reikai.domain.download.QueuedChapter
import reikai.domain.download.renameDownloadFolder
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadProvider
import reikai.novel.download.NovelDownloadStore
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager

/**
 * Carries the downloads of each copy the upgrade's dedupe merged away to the entry it merged into, by one rule
 * for both types: a download folder named by the copy's title takes the survivor's title when the survivor has
 * none and is merged into the survivor's when it has one, and a saved queue row naming the copy or a merged
 * chapter row names the survivor's instead, or goes. Nothing is overwritten, and a file is deleted only once a
 * checked copy stands in its place. Rules: docs/dev/subsystems/data-and-backup.md.
 */
@Inject
class MergedDuplicateDownloads(
    context: Context,
    private val downloadProvider: DownloadProvider,
    private val downloadCache: DownloadCache,
    private val downloadStore: DownloadStore,
    private val sourceManager: SourceManager,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val novelProvider: NovelDownloadProvider,
    private val novelCache: NovelDownloadCache,
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
) {

    private val novelStore = NovelDownloadStore(context, novelChapterRepository)

    suspend fun remapQueues(duplicates: List<MergedDuplicate>, chapters: List<MergedDuplicateChapter>) {
        // An upgrade that merged nothing leaves both queues untouched
        if (duplicates.isEmpty() && chapters.isEmpty()) return

        val queue = downloadStore.persisted()
        downloadStore.replacePersisted(
            queue,
            remapQueue(queue, ContentType.MANGA, duplicates, chapters) { getChapter.await(it) != null },
        )
        val novelQueue = novelStore.persisted()
        novelStore.replacePersisted(
            novelQueue,
            remapQueue(novelQueue, ContentType.NOVELS, duplicates, chapters) {
                novelChapterRepository.getById(it) !=
                    null
            },
        )
    }

    /**
     * The folders, named by title; the lowest id goes first, so it takes the survivor's name. False while a folder is
     * left to merge, for a later launch to try again.
     */
    suspend fun carryFolders(duplicates: List<MergedDuplicate>): Boolean {
        val carried = duplicates.sortedBy { it.discardedId }.map { duplicate ->
            duplicate to runCatchingCancellable { carryFolder(duplicate) }
                .onFailure {
                    logcat(LogPriority.WARN, it) {
                        "Merged-duplicate download folder carry failed: ${duplicate.discardedId}"
                    }
                }
                .getOrDefault(FolderCarry(finished = false, changed = false))
        }
        val changed = carried.filter { it.second.changed }.map { it.first.contentType }
        if (ContentType.MANGA in changed) downloadCache.invalidateCache()
        if (ContentType.NOVELS in changed) novelCache.invalidate()
        return carried.all { it.second.finished }
    }

    private suspend fun carryFolder(duplicate: MergedDuplicate): FolderCarry {
        when (duplicate.contentType) {
            ContentType.MANGA -> {
                val survivor = getManga.await(duplicate.survivorId) ?: return NOTHING_TO_DO
                val survivorName = downloadProvider.getMangaDirName(survivor.title)
                // Checked before the source lookup, which waits for extensions to load
                if (survivorName == downloadProvider.getMangaDirName(duplicate.discardedTitle)) return NOTHING_TO_DO
                val source = sourceManager.getOrStub(survivor.source)
                return moveFolder(
                    discarded = downloadProvider.findMangaDir(duplicate.discardedTitle, source),
                    survivor = downloadProvider.findMangaDir(survivor.title, source),
                    survivorName = survivorName,
                )
            }
            ContentType.NOVELS -> {
                val survivor = novelRepository.getById(duplicate.survivorId) ?: return NOTHING_TO_DO
                return moveFolder(
                    discarded = novelProvider.findNovelDir(survivor.copy(title = duplicate.discardedTitle)),
                    survivor = novelProvider.findNovelDir(survivor),
                    survivorName = novelProvider.novelDirName(survivor),
                )
            }
            ContentType.ALL -> return NOTHING_TO_DO
        }
    }

    /**
     * Renames the copy's folder to the survivor's name, in place, by [renameDownloadFolder]. A survivor with a
     * folder of its own has the copy's merged into it instead, by [DownloadFolderMerge].
     */
    private fun moveFolder(discarded: UniFile?, survivor: UniFile?, survivorName: String): FolderCarry {
        if (discarded == null || discarded.name == survivorName) return NOTHING_TO_DO
        // A case-blind disk finds the copy itself under a name apart only in letter case, so then only a folder
        // listed under the survivor's exact name is its own
        val own = if (discarded.name.equals(survivorName, ignoreCase = true)) {
            discarded.parentFile?.listFiles()?.firstOrNull { it.name == survivorName }
        } else {
            survivor
        }
        if (own != null) return DownloadFolderMerge.merge(from = discarded, into = own)
        val renamed = renameDownloadFolder(discarded, survivorName)
        return FolderCarry(finished = renamed, changed = renamed)
    }

    /**
     * A row of a merged-away entry names the survivor, a row of a merged-away chapter the kept row with the same
     * url, and a row whose chapter is gone is dropped, so a restore never reads a deleted id. Where two rows land
     * on one chapter, the one further up the queue stays.
     */
    private suspend fun remapQueue(
        queue: List<QueuedChapter>,
        contentType: ContentType,
        duplicates: List<MergedDuplicate>,
        chapters: List<MergedDuplicateChapter>,
        chapterExists: suspend (Long) -> Boolean,
    ): List<QueuedChapter> {
        val survivors = duplicates.survivorIds(contentType)
        val chapterSurvivors = chapters.survivorIds(contentType)
        return queue.sortedBy { it.order }
            .map { row ->
                QueuedChapter(
                    entryId = survivors[row.entryId] ?: row.entryId,
                    chapterId = chapterSurvivors[row.chapterId] ?: row.chapterId,
                    order = row.order,
                )
            }
            .distinctBy { it.chapterId }
            .filter { chapterExists(it.chapterId) }
    }

    private companion object {
        val NOTHING_TO_DO = FolderCarry(finished = true, changed = false)
    }
}
