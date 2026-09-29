package reikai.data.novel

import reikai.domain.chapter.ArrivingChapter
import reikai.domain.chapter.StoredChapter
import reikai.domain.chapter.chapterArrivals
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.download.NovelDownloadManager
import reikai.novel.host.ChapterItem
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Novel-side parallel of the manga `syncChaptersWithSource`, in one transaction. A re-added chapter
 * inherits the read/bookmark state and `dateFetch` of the one it replaces, so it is not new. A [page]
 * sync matches against the whole novel, so a chapter that moved pages is re-tagged in place, and never
 * deletes: one page cannot tell a removed chapter from one that moved to a page this run did not fetch.
 * Null syncs the whole novel and drops what the source no longer lists. Predicting the next update is
 * the caller's, once per whole-novel sync ([predictNovelFetchInterval]).
 */
suspend fun syncChaptersWithNovelSource(
    rawSourceChapters: List<ChapterItem>,
    novel: Novel,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    libraryPreferences: LibraryPreferences,
    page: String? = null,
    novelDownloadManager: NovelDownloadManager? = null,
): NovelChapterSyncResult {
    if (rawSourceChapters.isEmpty()) throw Exception("No chapters found")

    val novelId = novel.id
    require(novelId > 0L) { "syncChaptersWithNovelSource requires a persisted novel (id > 0)" }

    val dbChapters = novelChapterRepository.getByNovelId(novelId)

    val sourceChapters = rawSourceChapters
        .distinctBy { it.path }
        .mapIndexed { i, item ->
            val draft = item.toNovelChapter(novelId, sourceOrder = i.toLong())
            // Recognize a number from the name only when the plugin gave none (lands as 0.0); a
            // plugin-supplied positive number is trusted as-is. ChapterRecognition strips the title.
            val number = ChapterRecognition.parseChapterNumber(
                novel.title,
                draft.name,
                draft.chapterNumber.takeIf { it > 0.0 },
            )
            // A paged sync stamps the transport index; otherwise keep the plugin's own page label.
            draft.copy(chapterNumber = number, page = page ?: draft.page)
        }

    val toAdd = mutableListOf<NovelChapter>()
    val toChange = mutableListOf<NovelChapter>()
    // (old, new) pairs for chapters whose title changed; their downloaded file is renamed post-commit.
    val downloadRenames = mutableListOf<Pair<NovelChapter, NovelChapter>>()

    // A novel stores a url once (51.sqm merged the copies earlier page-scoped syncs left)
    val dbByUrl = dbChapters.associateBy { it.url }
    val sourceUrls = sourceChapters.mapTo(mutableSetOf()) { it.url }
    val toDelete = if (page == null) dbChapters.filterNot { it.url in sourceUrls } else emptyList()

    val managedUrls = mutableSetOf<String>()
    for (sourceChapter in sourceChapters) {
        if (sourceChapter.url in managedUrls) continue
        managedUrls += sourceChapter.url

        val dbChapter = dbByUrl[sourceChapter.url]
        if (dbChapter == null) {
            toAdd.add(sourceChapter)
        } else if (shouldUpdateDbNovelChapter(dbChapter, sourceChapter)) {
            val updated = dbChapter.copy(
                name = sourceChapter.name,
                dateUpload = sourceChapter.dateUpload,
                chapterNumber = sourceChapter.chapterNumber,
                sourceOrder = sourceChapter.sourceOrder,
                page = sourceChapter.page,
            )
            toChange.add(updated)
            // A re-titled chapter changes its stable-name download path; queue its file rename below.
            if (dbChapter.name != sourceChapter.name) downloadRenames += dbChapter to updated
        }
    }

    if (toAdd.isEmpty() && toDelete.isEmpty() && toChange.isEmpty()) return NovelChapterSyncResult.UNCHANGED

    val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
        .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW)
    // Sources list newest first, so the kernel counts fetch dates down from now in that order.
    val arrivals = chapterArrivals(
        added = toAdd.map { ArrivingChapter(it.chapterNumber, it.read, it.bookmark) },
        stored = dbChapters.map { it.toStoredChapter() },
        removed = toDelete.map { it.toStoredChapter() },
        markDuplicateAsRead = markDuplicateAsRead,
        now = System.currentTimeMillis(),
    )
    val changedOrDuplicateReadUrls = toAdd.zip(arrivals)
        .filter { (_, arrival) -> arrival.isChangedOrDuplicate }
        .mapTo(mutableSetOf()) { (chapter, _) -> chapter.url }
    val updatedToAdd = toAdd.zip(arrivals) { chapter, arrival ->
        chapter.copy(dateFetch = arrival.dateFetch, read = arrival.read, bookmark = arrival.bookmark)
    }

    val insertedChapters = novelChapterRepository.updateFromRemote(toDelete.map { it.id }, updatedToAdd, toChange)

    // novels.last_update tracks the last time the chapter list changed at all; only on a real change.
    novelRepository.update(NovelUpdate(novel.id) { lastUpdate = System.currentTimeMillis() })

    // Relocate any downloaded file whose chapter was re-titled, so recognition follows the new name
    // (mirrors the manga rename-on-sync). No-op when the chapter isn't downloaded.
    downloadRenames.forEach { (old, new) -> novelDownloadManager?.renameChapter(novel, old, new) }

    return NovelChapterSyncResult(insertedChapters.filterNot { it.url in changedOrDuplicateReadUrls }, changed = true)
}

private fun NovelChapter.toStoredChapter() = StoredChapter(chapterNumber, read, bookmark, dateFetch)

private fun shouldUpdateDbNovelChapter(dbChapter: NovelChapter, sourceChapter: NovelChapter): Boolean =
    dbChapter.name != sourceChapter.name ||
        dbChapter.dateUpload != sourceChapter.dateUpload ||
        dbChapter.chapterNumber != sourceChapter.chapterNumber ||
        dbChapter.sourceOrder != sourceChapter.sourceOrder ||
        dbChapter.page != sourceChapter.page
