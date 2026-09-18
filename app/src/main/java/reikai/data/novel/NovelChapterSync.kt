package reikai.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsOne
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
import tachiyomi.data.Database
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Novel-side parallel of the manga `syncChaptersWithSource`: reconcile a freshly-parsed source chapter
 * list against the stored `novel_chapters` rows, in one transaction. A re-added chapter inherits the
 * read/bookmark state and `dateFetch` of the one it replaces, so it does not bubble up as new. [page]
 * scopes the sync to one page of a paged source, so a novel that flips from unpaged to paged re-tags in
 * place instead of duplicating; null syncs the whole novel. Returns (inserted, deleted), each excluding
 * a pure duplicate-read carry-over so a new-chapter signal does not fire on reorders.
 */
suspend fun syncChaptersWithNovelSource(
    rawSourceChapters: List<ChapterItem>,
    novel: Novel,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    database: Database,
    libraryPreferences: LibraryPreferences,
    page: String? = null,
    novelDownloadManager: NovelDownloadManager? = null,
    manualFetch: Boolean = false,
    fetchWindow: Pair<Long, Long> = Pair(0, 0),
): Pair<List<NovelChapter>, List<NovelChapter>> {
    if (rawSourceChapters.isEmpty()) throw Exception("No chapters found")

    val novelId = novel.id
    require(novelId > 0L) { "syncChaptersWithNovelSource requires a persisted novel (id > 0)" }

    val dbChapters = if (page != null) {
        // This page's rows + any unpaged remnants from a prior single-page state; the latter get
        // re-tagged (toChange) or deduped here rather than left as cross-page duplicates.
        novelChapterRepository.getByNovelIdAndPage(novelId, page) +
            novelChapterRepository.getByNovelIdAndPage(novelId, "")
    } else {
        novelChapterRepository.getByNovelId(novelId)
    }

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

    val duplicates = dbChapters.groupBy { it.url }
        .filter { it.value.size > 1 }
        .flatMap { (_, chapters) -> chapters.drop(1) }
    val notInSource = dbChapters.filterNot { dbChapter -> sourceChapters.any { it.url == dbChapter.url } }
    val toDelete = duplicates + notInSource

    val managedUrls = mutableSetOf<String>()
    for (sourceChapter in sourceChapters) {
        if (sourceChapter.url in managedUrls) continue
        managedUrls += sourceChapter.url

        val dbChapter = dbChapters.find { it.url == sourceChapter.url }
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

    if (toAdd.isEmpty() && toDelete.isEmpty() && toChange.isEmpty()) {
        // As manga's sync: an unchanged list still moves a prediction that has fallen behind the window.
        if (manualFetch || novel.fetchInterval == 0 || novel.nextUpdate < fetchWindow.first) {
            updateNovelFetchInterval(novel, novelChapterRepository, novelRepository, fetchWindow)
        }
        return emptyList<NovelChapter>() to emptyList()
    }

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

    val insertedChapters = mutableListOf<NovelChapter>()
    database.transaction {
        toDelete.forEach { database.novel_chaptersQueries.delete(it.id) }

        for (chapter in updatedToAdd) {
            database.novel_chaptersQueries.insert(
                novelId = chapter.novelId,
                url = chapter.url,
                name = chapter.name,
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastTextProgress = chapter.lastTextProgress,
                chapterNumber = chapter.chapterNumber,
                sourceOrder = chapter.sourceOrder,
                dateFetch = chapter.dateFetch,
                dateUpload = chapter.dateUpload,
                page = chapter.page,
            )
            val insertedId = database.novel_chaptersQueries.selectLastInsertedRowId().awaitAsOne()
            insertedChapters += chapter.copy(id = insertedId)
        }

        for (chapter in toChange) {
            // Null every column but the changed metadata so coalesce preserves read/bookmark/progress.
            database.novel_chaptersQueries.update(
                novelId = null,
                url = null,
                name = chapter.name,
                read = null,
                bookmark = null,
                lastTextProgress = null,
                chapterNumber = chapter.chapterNumber,
                sourceOrder = chapter.sourceOrder,
                dateFetch = null,
                dateUpload = chapter.dateUpload,
                page = chapter.page,
                chapterId = chapter.id,
            )
        }
    }

    // Before last_update moves, which the prediction counts from.
    updateNovelFetchInterval(novel, novelChapterRepository, novelRepository, fetchWindow)
    // novels.last_update tracks the last time the chapter list changed at all; only on a real change.
    novelRepository.update(NovelUpdate(id = novel.id, lastUpdate = System.currentTimeMillis()))

    // Relocate any downloaded file whose chapter was re-titled, so recognition follows the new name
    // (mirrors the manga rename-on-sync). No-op when the chapter isn't downloaded.
    downloadRenames.forEach { (old, new) -> novelDownloadManager?.renameChapter(novel, old, new) }

    return insertedChapters.filterNot { it.url in changedOrDuplicateReadUrls } to
        toDelete.filterNot { it.url in changedOrDuplicateReadUrls }
}

private fun NovelChapter.toStoredChapter() = StoredChapter(chapterNumber, read, bookmark, dateFetch)

private fun shouldUpdateDbNovelChapter(dbChapter: NovelChapter, sourceChapter: NovelChapter): Boolean =
    dbChapter.name != sourceChapter.name ||
        dbChapter.dateUpload != sourceChapter.dateUpload ||
        dbChapter.chapterNumber != sourceChapter.chapterNumber ||
        dbChapter.sourceOrder != sourceChapter.sourceOrder ||
        dbChapter.page != sourceChapter.page
