package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.category.GetNovelCategories
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter

/**
 * The new chapters of a novel the "download new chapters" setting queues: the twin of the manga
 * FilterChaptersForDownload, so the update job and a details refresh apply one rule.
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
        val included = preferences.downloadNewChapterCategories().get().map { it.toLong() }
        val excluded = preferences.downloadNewChapterCategoriesExclude().get().map { it.toLong() }
        if (included.isEmpty() && excluded.isEmpty()) return true
        val categories = getNovelCategories.awaitByNovelId(novel.id).map { it.id }.ifEmpty { listOf(0L) }
        return categoryGate(categories, included, excluded)
    }
}

/** Include/exclude category predicate shared by the download and update gates: exclude wins; an empty
 *  include set means "all not excluded". Callers short-circuit the no-filter case before the DB read. */
internal fun categoryGate(categories: List<Long>, included: List<Long>, excluded: List<Long>): Boolean = when {
    categories.any { it in excluded } -> false
    included.isEmpty() -> true
    else -> categories.any { it in included }
}
