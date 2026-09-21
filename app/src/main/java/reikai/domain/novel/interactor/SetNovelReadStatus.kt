package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.model.NovelChapter
import reikai.domain.track.source.ChapterWrite
import reikai.domain.track.source.SourceTrackerDispatcher
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat

/**
 * Central "mark novel chapters read/unread" interactor, the novel twin of
 * [eu.kanade.domain.chapter.interactor.SetReadStatus]. Flips the read flag and, when marking read,
 * deletes the downloaded copies per novel when "delete after marked as read" is on (reusing
 * [DeleteNovelChaptersAfterRead], which owns the pref, excluded-category and bookmark guards). Every
 * novel mark-read site routes through this, so read and delete-after-read cannot drift. Tracker sync
 * stays a separate concern the screens call themselves, as the manga twin does.
 */
@Inject
class SetNovelReadStatus(
    private val chapterRepository: NovelChapterRepository,
    private val deleteAfterRead: DeleteNovelChaptersAfterRead,
    private val sourceTracker: SourceTrackerDispatcher,
) {

    suspend fun await(read: Boolean, chapters: List<NovelChapter>): Result = withNonCancellableContext {
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

        if (read) {
            toUpdate.groupBy { it.novelId }.forEach { (novelId, chs) ->
                deleteAfterRead.await(novelId, chs)
            }
        }

        Result.Success
    }

    sealed interface Result {
        data object Success : Result
        data object NoChapters : Result
        data class InternalError(val error: Throwable) : Result
    }
}
