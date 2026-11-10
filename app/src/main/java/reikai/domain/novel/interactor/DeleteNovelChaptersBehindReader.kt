package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.CopyMark
import reikai.domain.download.NovelRemovableDownloads
import reikai.domain.library.ContentType
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.expandToUnits
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelChapter
import reikai.domain.reader.ChapterIncognito
import reikai.domain.reader.chapterToDeleteBehind
import reikai.domain.reader.duplicatesOfRead
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadPendingDeleter
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * "After reading automatically delete" in the novel reader, as Mihon's reader runs it: finishing a
 * chapter queues the one the slots retire, picked by [chapterToDeleteBehind] like manga's
 * `ReaderViewModel.deleteChapterIfNeeded`, and leaving the reader deletes the queue. So a chapter just
 * finished is still on disk to page back to. The sibling of [DeleteNovelChaptersAfterRead], which
 * fires on marking by hand.
 */
@Inject
class DeleteNovelChaptersBehindReader(
    private val novelPreferences: NovelPreferences,
    private val removableDownloads: NovelRemovableDownloads,
    // Deferred for the same reason as in [DeleteNovelChaptersAfterRead]: building the manager resumes
    // the persisted download queue, and a reader open must not do that.
    private val downloadManager: () -> NovelDownloadManager,
    private val chapterRepository: NovelChapterRepository,
    private val pendingDeleter: NovelDownloadPendingDeleter,
    private val libraryPreferences: LibraryPreferences,
    private val copyMarks: ChapterCopyMarkRepository,
) {

    /** [orderedIds] is the session's reading order, so the slots count positions the user actually
     *  moves through rather than raw chapter numbers. [memberIds] and [stitch] are the merge group's,
     *  as [reikai.presentation.reader.text.NovelChapterFinish] takes them. */
    suspend fun await(
        orderedIds: List<Long>,
        readChapterId: Long,
        memberIds: List<Long>,
        stitch: List<ChapterUnit>,
        incognito: ChapterIncognito,
    ) {
        val slots = novelPreferences.removeAfterReadSlots().get()
        val targetId = orderedIds.chapterToDeleteBehind(readChapterId, slots) { it } ?: return
        val target = chapterRepository.getById(targetId) ?: return
        if (!target.read) return
        // The copies "mark duplicate read" marked with it go too, as manga's trim takes them
        // (DeleteDuplicatesBehindReaderConformanceTest).
        val duplicates = chapterRepository.duplicatesOfFinished(
            target,
            memberIds,
            stitch,
            libraryPreferences,
            incognito,
            copyMarks.loadedMarks(ContentType.NOVELS),
        )
            .map { it.copy(read = true) }
        val removable = removableDownloads(listOf(target) + duplicates)
        if (removable.isNotEmpty()) pendingDeleter.addChapters(removable)
    }

    /** Deletes what [await] queued. The host calls it on leaving the reader. */
    suspend fun deletePending() {
        val ids = pendingDeleter.takePendingChapterIds()
        if (ids.isEmpty()) return
        downloadManager().deleteChapters(ids.mapNotNull { chapterRepository.getById(it) })
    }
}

/**
 * [chapter]'s copies across [memberIds] that "mark duplicate read" reaches, or none while it is off.
 * A private source's copy is left out, as manga's: it is neither marked read nor deleted.
 */
internal suspend fun NovelChapterRepository.duplicatesOfFinished(
    chapter: NovelChapter,
    memberIds: List<Long>,
    stitch: List<ChapterUnit>,
    libraryPreferences: LibraryPreferences,
    incognito: ChapterIncognito,
    marks: Map<Long, Map<String, CopyMark>>,
): List<NovelChapter> {
    val markDuplicates = libraryPreferences.markDuplicateReadChapterAsRead.get()
        .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
    if (!markDuplicates) return emptyList()
    return memberIds.flatMap { getByNovelId(it) }.duplicatesOfRead(
        chapter,
        expandToUnits(setOf(chapter.id), stitch),
        numberOf = { it.chapterNumber },
        nameOf = { it.name },
        idOf = { it.id },
        originOf = { it.scanlator },
        ownerOf = { it.novelId },
        markOf = { marks[it.novelId]?.get(it.url) },
    ).filterNot { incognito.of(it.novelId) }
}
