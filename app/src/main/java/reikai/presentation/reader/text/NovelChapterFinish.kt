package reikai.presentation.reader.text

import android.content.Context
import eu.kanade.domain.track.service.TrackPreferences
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.expandToUnits
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.track.TrackNovelChapter
import reikai.domain.reader.duplicatesOfRead
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Finishing a chapter in a novel reader, once a session ([NovelCompletionLatch]): the mark, what "mark
 * duplicate read" reaches, the tracker push, then the caller's trim behind the reader. Every novel
 * reader finishes through this, so the readers cannot drift; manga's twin,
 * `ReaderViewModel.updateChapterProgressOnComplete`, is pinned to it by [duplicatesOfRead]. Keyed on
 * the chapter's own novel: a merged session reads several, and a fling finishes chapters of another
 * source than the one it lands on.
 */
class NovelChapterFinish(
    private val chapterRepo: NovelChapterRepository,
    private val setNovelReadStatus: SetNovelReadStatus,
    private val libraryPreferences: LibraryPreferences,
    private val trackPreferences: TrackPreferences,
    private val trackNovelChapter: TrackNovelChapter,
    private val context: Context,
) {

    private val completion = NovelCompletionLatch()

    /** [memberIds] are the merge group's novels, the chapter's own alone when ungrouped, and [stitch]
     *  the group's stored stitch. [trimBehind] runs after the mark, so the chapter it retires is read. */
    suspend fun finish(
        chapter: NovelChapter,
        memberIds: List<Long>,
        stitch: List<ChapterUnit>,
        trimBehind: suspend () -> Unit,
    ) {
        if (!completion.claim(chapter.id)) return
        val markDuplicates = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        val duplicates = if (!markDuplicates) {
            emptyList()
        } else {
            memberIds.flatMap { chapterRepo.getByNovelId(it) }.duplicatesOfRead(
                chapter,
                expandToUnits(setOf(chapter.id), stitch),
                numberOf = { it.chapterNumber },
                idOf = { it.id },
                ownerOf = { it.novelId },
            )
        }
        // SetNovelReadStatus also honours "delete after marked as read".
        setNovelReadStatus.await(true, listOf(chapter) + duplicates)
        if (trackPreferences.autoUpdateTrack.get()) {
            trackNovelChapter.await(context, chapter.novelId, chapter.chapterNumber)
        }
        trimBehind()
    }

    /** Unmarked, a chapter can be finished again, and finishing is what reaches the trackers. */
    fun release(chapterIds: Collection<Long>) = completion.release(chapterIds)
}
