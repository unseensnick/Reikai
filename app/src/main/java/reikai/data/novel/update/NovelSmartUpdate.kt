package reikai.data.novel.update

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import reikai.data.novel.NovelStatusCode
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Whether the library update fetches [novel], by the manga job's skip rules in its order: set to fetch
 * once with its chapters already in, then the Smart update [restrictions]: completed, with unread,
 * unstarted, and not due before the end of [fetchWindow]. [loadChapters] runs only when a rule needs the chapters.
 */
suspend fun novelPassesSmartUpdate(
    novel: Novel,
    restrictions: Set<String>,
    fetchWindow: Pair<Long, Long>,
    loadChapters: suspend () -> List<NovelChapter>,
): Boolean {
    var loaded: List<NovelChapter>? = null
    suspend fun chapters(): List<NovelChapter> = loaded ?: loadChapters().also { loaded = it }

    if (novel.updateStrategy == UpdateStrategy.ONLY_FETCH_ONCE && chapters().isNotEmpty()) return false
    if (LibraryPreferences.MANGA_NON_COMPLETED in restrictions && novel.status == NovelStatusCode.COMPLETED.toLong()) {
        return false
    }
    if (LibraryPreferences.MANGA_HAS_UNREAD in restrictions && chapters().any { !it.read }) return false
    // A novel with no chapters yet is not unstarted, as manga's rule checks there are chapters first.
    if (LibraryPreferences.MANGA_NON_READ in restrictions &&
        chapters().let { it.isNotEmpty() && it.none { c -> c.read } }
    ) {
        return false
    }
    return !(LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in restrictions && novel.nextUpdate > fetchWindow.second)
}
