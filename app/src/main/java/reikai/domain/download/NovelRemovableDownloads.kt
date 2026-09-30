package reikai.domain.download

import dev.zacsweers.metro.Inject
import reikai.domain.category.GetNovelCategories
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelChapter

/**
 * [removableDownloads] over novels' own settings, each novel's chapters judged by that novel's
 * categories. Novels' automatic removal asks this, delete-after-read and the reader's trim, as manga's
 * filters in DownloadManager before it deletes; a Delete by hand does not.
 */
@Inject
class NovelRemovableDownloads(
    private val novelPreferences: NovelPreferences,
    private val getNovelCategories: GetNovelCategories,
) {
    suspend operator fun invoke(chapters: List<NovelChapter>): List<NovelChapter> {
        val excluded = novelPreferences.removeExcludeCategories().get()
        val allowBookmarked = novelPreferences.removeBookmarkedChapters().get()
        return chapters.groupBy { it.novelId }.flatMap { (novelId, owned) ->
            removableDownloads(
                owned,
                excluded = excluded,
                allowBookmarked = allowBookmarked,
                isRead = NovelChapter::read,
                isBookmarked = NovelChapter::bookmark,
            ) { getNovelCategories.awaitByNovelId(novelId).map { it.id } }
        }
    }
}
