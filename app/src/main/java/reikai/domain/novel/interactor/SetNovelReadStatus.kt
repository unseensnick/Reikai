package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.track.PushNovelUnread
import reikai.domain.track.source.ChapterWrite
import reikai.domain.track.source.SourceTrackerDispatcher
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat

/**
 * Central "mark novel chapters read/unread" interactor, twin of
 * [eu.kanade.domain.chapter.interactor.SetReadStatus], pinned by MarkReadDeleteConformanceTest (the delete
 * after read) and ReadStatusHandOffConformanceTest (what a source's own tracker is handed). Marking
 * read also deletes the downloaded copies when "delete after marked as read" is on, through
 * [DeleteNovelChaptersAfterRead], except from the reader, which marks through [awaitFinishedInReader].
 * Trackers other than a source's own sync a read from the screens; an unread reaches [PushNovelUnread].
 */
@Inject
class SetNovelReadStatus(
    private val chapterRepository: NovelChapterRepository,
    private val deleteAfterRead: DeleteNovelChaptersAfterRead,
    private val sourceTracker: SourceTrackerDispatcher,
    private val pushNovelUnread: PushNovelUnread,
) {

    suspend fun await(read: Boolean, chapters: List<NovelChapter>): Result = write(read, chapters) { written ->
        if (read) {
            // As written, so a category kept from removal keeps them.
            deleteAfterRead.await(written.map { it.copy(read = true) })
        } else {
            // A chapter merely started was never marked on a site, so only one that was read moves it back.
            pushNovelUnread.launch(written.filter { it.read })
        }
    }

    /**
     * Marks [chapters] read on finishing them in the reader. Mihon's reader writes the flag without
     * "delete after marked as read", which is for marking by hand; the reader's own slot rule
     * ([DeleteNovelChaptersBehindReader]) trims behind it instead.
     */
    suspend fun awaitFinishedInReader(chapters: List<NovelChapter>): Result = write(true, chapters) {}

    private suspend fun write(
        read: Boolean,
        chapters: List<NovelChapter>,
        afterWrite: suspend (List<NovelChapter>) -> Unit,
    ): Result = withNonCancellableContext {
        val toUpdate = chapters.filter {
            when (read) {
                true -> !it.read
                false -> it.read || it.lastTextProgress > 0L
            }
        }
        if (toUpdate.isEmpty()) return@withNonCancellableContext Result.NoChapters

        try {
            chapterRepository.setReadBulk(toUpdate.map { it.id }, read)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        sourceTracker.readStateWritten(read, toUpdate.map { ChapterWrite(EntryId.Novel(it.novelId), it.id, it.read) })
        afterWrite(toUpdate)
        Result.Success
    }

    sealed interface Result {
        data object Success : Result
        data object NoChapters : Result
        data class InternalError(val error: Throwable) : Result
    }
}
