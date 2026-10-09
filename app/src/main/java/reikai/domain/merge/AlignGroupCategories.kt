package reikai.domain.merge

import dev.zacsweers.metro.Inject
import reikai.domain.library.ContentType
import reikai.domain.manga.MangaGroupCategories
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelGroupCategories
import reikai.domain.novel.NovelMergeManager

/**
 * Puts every group of one content type in its first library member's categories, for the writes that
 * file entries one at a time and can leave a group disagreeing: the upgrade that heals existing groups
 * and a backup restore.
 */
@Inject
class AlignGroupCategories(
    private val mangaMergeManager: MangaMergeManager,
    private val mangaCategories: MangaGroupCategories,
    private val novelMergeManager: NovelMergeManager,
    private val novelCategories: NovelGroupCategories,
) {

    suspend fun align(contentType: ContentType) {
        when (contentType) {
            ContentType.MANGA -> mangaCategories.alignEveryGroup(mangaMergeManager)
            ContentType.NOVELS -> novelCategories.alignEveryGroup(novelMergeManager)
            ContentType.ALL -> error("A group belongs to one content type")
        }
    }
}
