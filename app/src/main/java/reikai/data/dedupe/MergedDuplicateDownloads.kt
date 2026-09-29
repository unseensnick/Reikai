package reikai.data.dedupe

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.DownloadStore
import eu.kanade.tachiyomi.data.download.Downloader
import logcat.LogPriority
import reikai.domain.dedupe.MergedDuplicate
import reikai.domain.dedupe.MergedDuplicateChapter
import reikai.domain.download.QueuedChapter
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadProvider
import reikai.novel.download.NovelDownloadStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager

/**
 * Carries the downloads of each copy the upgrade's dedupe merged away to the entry it merged into, by one rule
 * for both types: a download folder named by the copy's title takes the survivor's title when the survivor has
 * none, and a saved queue row naming the copy or a merged chapter row names the survivor's instead, or goes.
 * Nothing is deleted or overwritten. Rules: docs/dev/plans/mihon-schema-rewrite.md.
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

    suspend fun carry(duplicates: List<MergedDuplicate>, chapters: List<MergedDuplicateChapter>) {
        // An upgrade that merged nothing leaves both queues untouched
        if (duplicates.isEmpty() && chapters.isEmpty()) return
        val moved = duplicates.sortedBy { it.discardedId }.filter { duplicate ->
            runCatching { carryFolder(duplicate) }
                .onFailure {
                    logcat(LogPriority.WARN, it) {
                        "Merged-duplicate download folder carry failed: ${duplicate.discardedId}"
                    }
                }
                .getOrDefault(false)
        }
        if (moved.any { it.contentType == ContentType.MANGA }) downloadCache.invalidateCache()
        if (moved.any { it.contentType == ContentType.NOVELS }) novelCache.invalidate()

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

    private suspend fun carryFolder(duplicate: MergedDuplicate): Boolean {
        when (duplicate.contentType) {
            ContentType.MANGA -> {
                val survivor = getManga.await(duplicate.survivorId) ?: return false
                val survivorName = downloadProvider.getMangaDirName(survivor.title)
                // Checked before the source lookup, which waits for extensions to load
                if (survivorName == downloadProvider.getMangaDirName(duplicate.discardedTitle)) return false
                val source = sourceManager.getOrStub(survivor.source)
                return moveFolder(
                    discarded = downloadProvider.findMangaDir(duplicate.discardedTitle, source),
                    survivor = downloadProvider.findMangaDir(survivor.title, source),
                    survivorName = survivorName,
                )
            }
            ContentType.NOVELS -> {
                val survivor = novelRepository.getById(duplicate.survivorId) ?: return false
                return moveFolder(
                    discarded = novelProvider.findNovelDir(survivor.copy(title = duplicate.discardedTitle)),
                    survivor = novelProvider.findNovelDir(survivor),
                    survivorName = novelProvider.novelDirName(survivor),
                )
            }
            ContentType.ALL -> return false
        }
    }

    /**
     * Renames the copy's folder to the survivor's name, in place, through a temporary name when only the letter
     * case differs, as both engines' title renames do. A survivor with a folder of its own keeps it and the copy's
     * is left as it is: storage offers no move between folders, so merging the two would mean copy and delete.
     */
    private fun moveFolder(discarded: UniFile?, survivor: UniFile?, survivorName: String): Boolean {
        if (discarded == null || survivor != null || discarded.name == survivorName) return false
        if (discarded.name.equals(survivorName, ignoreCase = true) &&
            !discarded.renameTo(survivorName + Downloader.TMP_DIR_SUFFIX)
        ) {
            return false
        }
        return discarded.renameTo(survivorName)
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
        val survivors = duplicates.filter {
            it.contentType == contentType
        }.associate { it.discardedId to it.survivorId }
        val chapterSurvivors = chapters.filter { it.contentType == contentType }
            .associate { it.discardedId to it.survivorId }
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
}
