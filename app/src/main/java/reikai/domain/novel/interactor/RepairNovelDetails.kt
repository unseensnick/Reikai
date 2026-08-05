package reikai.domain.novel.interactor

import logcat.LogPriority
import reikai.data.novel.refreshNovelFromSource
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Finds library novels showing another novel's details and re-fetches them from their own source.
 * A plugin-host bug let a slow call's result land on the next novel in a bulk run, so that entry kept
 * its own url and chapters but wore a same-source neighbour's title, cover, author and description.
 * The host no longer does that, but rows written before the fix stay wrong until something refreshes
 * them, and the only visible symptom is a cover the user happens to recognise.
 */
class RepairNovelDetails(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val downloadManager: NovelDownloadManager = Injekt.get(),
    private val database: Database = Injekt.get(),
) {

    data class Result(val suspects: Int, val repaired: Int)

    suspend fun await(): Result {
        // Detect over every row, repair only library ones. The donor can be a non-favorite shadow row
        // left by opening a novel in Browse, and on a real corrupted device one victim was only
        // visible because of such a row; scanning the library alone missed it entirely.
        val suspects = findSuspects(novelRepository.getAll()).filter { it.favorite }
        var repaired = 0
        suspects.forEach { novel ->
            val source = sourceManager.get(novel.source) ?: return@forEach
            runCatching {
                refreshNovelFromSource(
                    novel,
                    source,
                    novelChapterRepository,
                    novelRepository,
                    database,
                    novelDownloadManager = downloadManager,
                )
            }
                .onSuccess { repaired++ }
                .onFailure { logcat(LogPriority.WARN, it) { "Novel detail repair failed: id=${novel.id}" } }
        }
        return Result(suspects = suspects.size, repaired = repaired)
    }

    companion object {
        /**
         * Two novels on the SAME source sharing a title and author but sitting at different urls: one
         * is wearing the other's details.
         *
         * Author is part of the key because title alone flags every same-titled work on a
         * user-generated source, and it costs no sensitivity: the mix-up copies the whole metadata
         * block, so a victim carries its donor's author too. Both members are returned rather than a
         * guess at the victim, since re-fetching resolves it either way for one wasted request.
         */
        fun findSuspects(novels: List<Novel>): List<Novel> =
            novels
                .filter { it.title.isNotBlank() }
                .groupBy { Triple(it.source, it.title.trim().lowercase(), it.author?.trim()?.lowercase()) }
                .values
                .filter { group -> group.distinctBy { it.url }.size > 1 }
                .flatten()
    }
}
