package reikai.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsOne
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.download.NovelDownloadManager
import reikai.novel.host.ChapterItem
import tachiyomi.data.Database
import tachiyomi.domain.chapter.service.ChapterRecognition
import java.util.TreeSet

/**
 * Novel-side parallel of the manga `syncChaptersWithSource`: reconcile a freshly-parsed source
 * chapter list against the stored `novel_chapters` rows, in one transaction. Stripped of manga-only
 * concerns (no scanlators, no download-manager hook, no duplicate-read library pref).
 *
 * Re-typed from the Yōkai helper onto the S1 repos + [Database] directly. Chapter-number recognition
 * runs only when the plugin gave none (drives sort + display); plugin-supplied numbers are trusted.
 * A re-added chapter (same recognized number as a just-deleted one) inherits its read/bookmark state
 * and original `dateFetch`, so it doesn't bubble up as "new".
 *
 * [page] scopes the sync to one page of a paged source: reconciliation runs against that page's stored
 * rows plus any leftover unpaged ("") rows (so a novel that flipped from unpaged to paged re-tags
 * those rows in place instead of duplicating them, e.g. a source whose pagination is a toggleable
 * setting), and each synced chapter is tagged with [page] as its transport index. Other numbered
 * pages are left untouched. Null = whole-novel sync (the unpaged / parseNovel path), which keeps each
 * chapter's own `ChapterItem.page` (volume label or empty).
 *
 * @return (newly inserted chapters, deleted chapters), each excluding entries whose only change was a
 *   duplicate-read carry-over, so a caller's "new chapters" signal doesn't fire on reorders.
 */
suspend fun syncChaptersWithNovelSource(
    rawSourceChapters: List<ChapterItem>,
    novel: Novel,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    database: Database,
    page: String? = null,
    novelDownloadManager: NovelDownloadManager? = null,
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
        return emptyList<NovelChapter>() to emptyList()
    }

    val deletedNumbers = TreeSet<Double>()
    val deletedReadNumbers = TreeSet<Double>()
    val deletedBookmarkedNumbers = TreeSet<Double>()
    toDelete.forEach {
        if (it.read) deletedReadNumbers.add(it.chapterNumber)
        if (it.bookmark) deletedBookmarkedNumbers.add(it.chapterNumber)
        deletedNumbers.add(it.chapterNumber)
    }

    val now = System.currentTimeMillis()
    val changedOrDuplicateReadUrls = mutableSetOf<String>()

    // Stagger date_fetch so newer-listed chapters get higher values; sources return most-to-least
    // recent. A re-added chapter reuses its deleted twin's state + original fetch date.
    var itemCount = toAdd.size
    val updatedToAdd = toAdd.map { addItem ->
        var dateFetch = now + itemCount--
        var read = addItem.read
        var bookmark = addItem.bookmark
        if (addItem.chapterNumber >= 0.0 && addItem.chapterNumber in deletedNumbers) {
            read = addItem.chapterNumber in deletedReadNumbers
            bookmark = addItem.chapterNumber in deletedBookmarkedNumbers
            toDelete.filter { it.chapterNumber == addItem.chapterNumber }
                .minByOrNull { it.dateFetch }
                ?.let { dateFetch = it.dateFetch }
            changedOrDuplicateReadUrls.add(addItem.url)
        }
        addItem.copy(dateFetch = dateFetch, read = read, bookmark = bookmark)
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

    // novels.last_update tracks the last time the chapter list changed at all; only on a real change.
    novelRepository.update(NovelUpdate(id = novel.id, lastUpdate = System.currentTimeMillis()))

    // Relocate any downloaded file whose chapter was re-titled, so recognition follows the new name
    // (mirrors the manga rename-on-sync). No-op when the chapter isn't downloaded.
    downloadRenames.forEach { (old, new) -> novelDownloadManager?.renameChapter(novel, old, new) }

    return insertedChapters.filterNot { it.url in changedOrDuplicateReadUrls } to
        toDelete.filterNot { it.url in changedOrDuplicateReadUrls }
}

private fun shouldUpdateDbNovelChapter(dbChapter: NovelChapter, sourceChapter: NovelChapter): Boolean =
    dbChapter.name != sourceChapter.name ||
        dbChapter.dateUpload != sourceChapter.dateUpload ||
        dbChapter.chapterNumber != sourceChapter.chapterNumber ||
        dbChapter.sourceOrder != sourceChapter.sourceOrder ||
        dbChapter.page != sourceChapter.page
