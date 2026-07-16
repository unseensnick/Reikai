package reikai.domain.novel

import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.model.NovelChapter

interface NovelChapterRepository {
    suspend fun getByNovelId(novelId: Long): List<NovelChapter>

    /** Reactive [getByNovelId]: re-emits on any write to this novel's chapters. */
    fun getByNovelIdAsFlow(novelId: Long): Flow<List<NovelChapter>>

    /** Chapters on a single page (transport index "1".."N") or volume label of a paged source. */
    suspend fun getByNovelIdAndPage(novelId: Long, page: String): List<NovelChapter>

    /** Reactive [getByNovelIdAndPage]: drives the lazy per-page chapter list. */
    fun getByNovelIdAndPageAsFlow(novelId: Long, page: String): Flow<List<NovelChapter>>

    /** Distinct non-empty page labels, for the page selector of label-grouped sources. */
    suspend fun getDistinctPages(novelId: Long): List<String>
    suspend fun getById(id: Long): NovelChapter?
    suspend fun getByUrlAndNovelId(url: String, novelId: Long): NovelChapter?
    suspend fun insert(chapter: NovelChapter): Long?
    suspend fun update(chapter: NovelChapter): Boolean

    /** Focused write for the reader's auto-save path; avoids the round-trip a full update needs. */
    suspend fun setLastTextProgress(id: Long, progress: Long): Boolean

    /**
     * Focused read/bookmark writes. Touch only the one column so a chapter object carrying a
     * synthetic `source_order` (a merged unified-list copy) can't overwrite the stored order.
     */
    suspend fun setRead(id: Long, read: Boolean): Boolean
    suspend fun setBookmark(id: Long, bookmark: Boolean): Boolean

    /**
     * Set read for many chapters in one transaction (marking unread also rewinds text progress).
     * One commit keeps mark-all on a large novel instant.
     */
    suspend fun setReadBulk(ids: List<Long>, read: Boolean): Boolean

    suspend fun delete(id: Long)
}
