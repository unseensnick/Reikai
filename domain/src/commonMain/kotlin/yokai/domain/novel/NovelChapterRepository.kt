package yokai.domain.novel

import kotlinx.coroutines.flow.Flow
import yokai.domain.novel.models.NovelChapter

interface NovelChapterRepository {
    suspend fun getByNovelId(novelId: Long): List<NovelChapter>

    /** Reactive [getByNovelId]: re-emits on any write to this novel's chapters (sync, mark-read,
     *  bookmark, progress), so a DB-first details screen updates without a manual refresh. */
    fun getByNovelIdAsFlow(novelId: Long): Flow<List<NovelChapter>>
    suspend fun getById(id: Long): NovelChapter?
    suspend fun getByUrlAndNovelId(url: String, novelId: Long): NovelChapter?
    suspend fun insert(chapter: NovelChapter): Long?
    suspend fun update(chapter: NovelChapter): Boolean
    /** Focused write for the reader's auto-save path; avoids the round-trip a full update needs. */
    suspend fun setLastTextProgress(id: Long, progress: Int): Boolean

    /** Focused read/bookmark writes. Touch only the one column so a chapter object carrying a
     *  synthetic `source_order` (a merged unified-list copy) can't overwrite the stored order. */
    suspend fun setRead(id: Long, read: Boolean): Boolean
    suspend fun setBookmark(id: Long, bookmark: Boolean): Boolean

    /** Toggle the offline-download flag. Dedicated single-column write so the download engine never
     *  round-trips the whole row (which could clobber a merged copy's synthetic source_order). */
    suspend fun setDownloaded(id: Long, downloaded: Boolean): Boolean
    suspend fun delete(id: Long)
}
