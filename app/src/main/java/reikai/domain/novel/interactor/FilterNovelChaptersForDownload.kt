package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.category.GetNovelCategories
import reikai.domain.category.matchesCategoryFilter
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter

/**
 * The new chapters of a novel the "download new chapters" setting queues: the twin of the manga
 * FilterChaptersForDownload, so the update job and a details refresh apply one rule. The two are
 * pinned by DownloadNewChaptersConformanceTest, which runs one suite over both.
 */
@Inject
class FilterNovelChaptersForDownload(
    private val chapterRepo: NovelChapterRepository,
    private val preferences: NovelPreferences,
    private val getNovelCategories: GetNovelCategories,
) {

    suspend fun await(novel: Novel, newChapters: List<NovelChapter>): List<NovelChapter> {
        if (newChapters.isEmpty() || !preferences.downloadNewChapters().get() || !shouldDownloadFor(novel)) {
            return emptyList()
        }
        if (!preferences.downloadNewUnreadChaptersOnly().get()) return newChapters
        val readNumbers = chapterRepo.getByNovelId(novel.id)
            .asSequence()
            .filter { it.read && it.chapterNumber >= 0.0 }
            .map { it.chapterNumber }
            .toSet()
        return newChapters.filterNot { it.chapterNumber in readNumbers }
    }

    private suspend fun shouldDownloadFor(novel: Novel): Boolean {
        if (!novel.favorite) return false
        val included = preferences.downloadNewChapterCategories().get().mapTo(mutableSetOf()) { it.toLong() }
        val excluded = preferences.downloadNewChapterCategoriesExclude().get().mapTo(mutableSetOf()) { it.toLong() }
        if (included.isEmpty() && excluded.isEmpty()) return true
        val categories = getNovelCategories.awaitByNovelId(novel.id).map { it.id }.ifEmpty { listOf(0L) }
        return matchesCategoryFilter(categories, included, excluded)
    }
}
