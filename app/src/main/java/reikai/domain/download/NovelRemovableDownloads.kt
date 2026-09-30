package reikai.domain.download

import dev.zacsweers.metro.Inject
import reikai.domain.category.GetNovelCategories
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelChapter

/**
 * [removableDownloads] over novels' own settings, each novel's chapters judged by that novel's
 * categories. The download manager's delete, delete-after-read and the reader's trim all ask this, as
 * manga's delete filters inside DownloadManager.
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
