package reikai.domain.novel.interactor

import logcat.LogPriority
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat

/**
 * Central "mark novel chapters read/unread" interactor, the novel twin of
 * [eu.kanade.domain.chapter.interactor.SetReadStatus]. Flips the read flag and, when marking read,
 * deletes the downloaded copies per novel when "delete after marked as read" is on (reusing
 * [DeleteNovelChaptersAfterRead], which owns the pref + excluded-category + bookmark guards). Every
 * novel mark-read site routes through this so read + delete-after-read can't drift between them.
 * Tracker sync stays a separate concern the details/reader screens call themselves, mirroring how the
 * manga twin leaves tracking out.
 */
class SetNovelReadStatus(
    private val chapterRepository: NovelChapterRepository,
    private val deleteAfterRead: DeleteNovelChaptersAfterRead,
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
