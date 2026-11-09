package reikai.presentation.reader.text

import android.content.Context
import eu.kanade.domain.track.service.TrackPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.library.ContentType
import reikai.domain.merge.ChapterUnit
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.duplicatesOfFinished
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.track.TrackNovelChapter
import reikai.domain.reader.ChapterIncognito
import reikai.domain.reader.duplicatesOfRead
import reikai.domain.track.TrackedChapterNumber
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
    private val trackedChapterNumber: TrackedChapterNumber,
    private val incognito: ChapterIncognito,
    private val copyMarks: ChapterCopyMarkRepository,
    private val scope: CoroutineScope,
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
        val duplicates = chapterRepo.duplicatesOfFinished(
            chapter,
            memberIds,
            stitch,
            libraryPreferences,
            incognito,
            copyMarks.marks(ContentType.NOVELS).value,
        )
        // Without "delete after marked as read", as in Mihon's reader: the trim behind is the reader's rule.
        setNovelReadStatus.awaitFinishedInReader(listOf(chapter) + duplicates)
        // Launched, as manga's is: a finish runs under the reader's write lock, which crossings share.
        // A sibling's copy (a source chip, a History resume) sends the trunk's number.
        if (trackPreferences.autoUpdateTrack.get()) {
            scope.launch {
                val number = trackedChapterNumber.ofNovels(listOf(chapter)).single()
                trackNovelChapter.await(context, chapter.novelId, number)
            }
        }
        trimBehind()
    }

    /** Unmarked, a chapter can be finished again, and finishing is what reaches the trackers. */
    fun release(chapterIds: Collection<Long>) = completion.release(chapterIds)
}
