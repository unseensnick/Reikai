package reikai.domain.novel.interactor

import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.setNovelFlag

/**
 * Per-novel chapter sort / filter / display writes, the novel twin of
 * [tachiyomi.domain.manga.interactor.SetMangaChapterFlags]. Each setter recomputes the packed
 * [Novel.chapterFlags] from the current value and writes only that column via a [NovelUpdate].
 * Setting a sort / filter / display also flips the matching local-override bit so the novel uses its
 * own value instead of the global default.
 */
class SetNovelChapterFlags(
    private val novelRepository: NovelRepository,
) {

    suspend fun awaitSetSortOrder(novel: Novel, sort: Long, descending: Boolean): Boolean {
        val direction = if (descending) NovelChapterFlags.SORT_DESC else NovelChapterFlags.SORT_ASC
        var flags = setNovelFlag(novel.chapterFlags, sort, NovelChapterFlags.SORTING_MASK)
        flags = setNovelFlag(flags, direction, NovelChapterFlags.SORT_DIR_MASK)
        flags = setNovelFlag(flags, NovelChapterFlags.SORT_LOCAL, NovelChapterFlags.SORT_LOCAL_MASK)
        return novelRepository.update(NovelUpdate(id = novel.id, chapterFlags = flags))
    }

    suspend fun awaitSetFilters(novel: Novel, read: Long, bookmarked: Long): Boolean {
        var flags = setNovelFlag(novel.chapterFlags, read, NovelChapterFlags.READ_MASK)
        flags = setNovelFlag(flags, bookmarked, NovelChapterFlags.BOOKMARKED_MASK)
        flags = setNovelFlag(flags, NovelChapterFlags.FILTER_LOCAL, NovelChapterFlags.FILTER_LOCAL_MASK)
        return novelRepository.update(NovelUpdate(id = novel.id, chapterFlags = flags))
    }

    suspend fun awaitSetHideTitles(novel: Novel, hide: Boolean): Boolean {
        val display = if (hide) NovelChapterFlags.DISPLAY_NUMBER else NovelChapterFlags.DISPLAY_NAME
        var flags = setNovelFlag(novel.chapterFlags, display, NovelChapterFlags.DISPLAY_MASK)
        flags = setNovelFlag(flags, NovelChapterFlags.SORT_LOCAL, NovelChapterFlags.SORT_LOCAL_MASK)
        return novelRepository.update(NovelUpdate(id = novel.id, chapterFlags = flags))
    }

    /** Drop this novel's local sort / filter overrides so the global defaults apply again. */
    suspend fun awaitClearLocalOverrides(novel: Novel): Boolean {
        var flags = setNovelFlag(novel.chapterFlags, 0L, NovelChapterFlags.SORT_LOCAL_MASK)
        flags = setNovelFlag(flags, 0L, NovelChapterFlags.FILTER_LOCAL_MASK)
        return novelRepository.update(NovelUpdate(id = novel.id, chapterFlags = flags))
    }
}
