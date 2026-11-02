package reikai.data.novel

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import reikai.domain.chapter.ArrivingChapter
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.chapter.StoredChapter
import reikai.domain.chapter.appliedTo
import reikai.domain.chapter.chapterArrivals
import reikai.domain.chapter.foundRelease
import reikai.domain.chapter.recognizeChapterNumbers
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.download.NovelDownloadManager
import reikai.novel.host.ChapterItem
import tachiyomi.data.chapter.ChapterSanitizer
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.service.LibraryPreferences
import kotlin.time.Clock

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
    numberOverrides: ChapterNumberOverrideRepository,
    page: String? = null,
    novelDownloadManager: NovelDownloadManager? = null,
): NovelChapterSyncResult {
    if (rawSourceChapters.isEmpty()) throw NoChaptersException()

    val novelId = novel.id
    require(novelId > 0L) { "syncChaptersWithNovelSource requires a persisted novel (id > 0)" }

    val dbChapters = novelChapterRepository.getByNovelId(novelId)

    val drafts = rawSourceChapters.toDrafts(novelId, novel.title, page)
    val draftUrls = drafts.mapTo(HashSet()) { it.url }
    // A page sync numbers the novel's whole stored list with its page in place, in page then listing
    // order, so a chapter at a page edge reads as one list reads it, whichever page arrived first. Its
    // source order is the place in that list too: a plugin's index restarts on every page, and every
    // reading order (details, reader, stitch) sorts by source order alone.
    val listed = if (page?.toLongOrNull() == null) {
        drafts
    } else {
        dbChapters.filter { it.url !in draftUrls && it.page != page && it.page.toLongOrNull() != null }
            .plus(drafts)
            .sortedWith(compareBy({ it.page.toLong() }, { it.sourceOrder }))
            .mapIndexed { i, chapter -> chapter.copy(sourceOrder = i.toLong()) }
    }
    // A number the user corrected replaces the source's, by the rule the manga sync applies too.
    val overridden = numberOverrides.getByOwner(ContentType.NOVELS, novelId).appliedTo(
        numbered(novel.title, listed),
        urlOf = { it.url },
        numberOf = { it.chapterNumber },
        withNumber = { chapter, number -> chapter.copy(chapterNumber = number) },
    )
    numberOverrides.updateSourceNumbers(ContentType.NOVELS, novelId, overridden.moved)
    val (sourceChapters, aroundPage) = overridden.chapters.partition { it.url in draftUrls }

    val toAdd = mutableListOf<NovelChapter>()
    val toChange = mutableListOf<NovelChapter>()
    // (old, new) pairs for chapters whose title changed; their downloaded file is renamed post-commit.
    val downloadRenames = mutableListOf<Pair<NovelChapter, NovelChapter>>()

    // A novel stores a url once (51.sqm merged the copies earlier page-scoped syncs left)
    val dbByUrl = dbChapters.associateBy { it.url }
    val sourceUrls = sourceChapters.mapTo(mutableSetOf()) { it.url }
    val sourceNumbers = overridden.chapters.associate { it.url to it.chapterNumber }
    val toDelete = if (page == null) dbChapters.filterNot { it.url in sourceUrls } else emptyList()

    val managedUrls = mutableSetOf<String>()
    for (sourceChapter in sourceChapters) {
        if (sourceChapter.url in managedUrls) continue
        managedUrls += sourceChapter.url

        val dbChapter = dbByUrl[sourceChapter.url]
        if (dbChapter == null) {
            toAdd.add(sourceChapter)
            continue
        }
        // A source that stops dating a chapter or naming its group keeps the stored value, as manga's
        // coalescing update does.
        val updated = dbChapter.copy(
            name = sourceChapter.name,
            dateUpload = sourceChapter.dateUpload ?: dbChapter.dateUpload,
            chapterNumber = sourceChapter.chapterNumber,
            sourceOrder = sourceChapter.sourceOrder,
            page = sourceChapter.page,
            scanlator = sourceChapter.scanlator ?: dbChapter.scanlator,
            rawChapterNumber = sourceChapter.rawChapterNumber,
        )
        if (updated != dbChapter) {
            toChange.add(updated)
            // A re-titled chapter changes its stable-name download path; queue its file rename below.
            if (dbChapter.name != sourceChapter.name) downloadRenames += dbChapter to updated
        }
    }
    // A chapter on another page that the page in place renumbered or moved.
    aroundPage.forEach { chapter ->
        val dbChapter = dbByUrl.getValue(chapter.url)
        if (chapter.chapterNumber != dbChapter.chapterNumber || chapter.sourceOrder != dbChapter.sourceOrder) {
            toChange.add(dbChapter.copy(chapterNumber = chapter.chapterNumber, sourceOrder = chapter.sourceOrder))
        }
    }

    if (toAdd.isEmpty() && toDelete.isEmpty() && toChange.isEmpty()) return NovelChapterSyncResult.UNCHANGED

    val now = Clock.System.now()
    val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
        .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW)
    // Sources list newest first, so the kernel counts fetch dates down from now in that order.
    val arrivals = chapterArrivals(
        added = toAdd.map { ArrivingChapter(it.chapterNumber, it.read, it.bookmark, it.dateUpload) },
        stored = dbChapters.map { it.toStoredChapter(sourceNumbers[it.url] ?: it.chapterNumber) },
        removed = toDelete.map { it.toStoredChapter() },
        markDuplicateAsRead = markDuplicateAsRead,
        now = now,
    )
    val changedOrDuplicateReadUrls = toAdd.zip(arrivals)
        .filter { (_, arrival) -> arrival.isChangedOrDuplicate }
        .mapTo(mutableSetOf()) { (chapter, _) -> chapter.url }
    val updatedToAdd = toAdd.zip(arrivals) { chapter, arrival ->
        chapter.copy(
            dateFetch = arrival.dateFetch,
            dateUpload = arrival.dateUpload,
            read = arrival.read,
            bookmark = arrival.bookmark,
        )
    }

    val insertedChapters = novelChapterRepository.updateFromRemote(toDelete.map { it.id }, updatedToAdd, toChange)

    // novels.last_update tracks the last time the chapter list changed at all; only on a real change.
    novelRepository.update(NovelUpdate(novel.id) { lastUpdate = now })

    // Relocate any downloaded file whose chapter was re-titled, so recognition follows the new name
    // (mirrors the manga rename-on-sync, pinned by RenameOnSyncConformanceTest). No-op when the chapter
    // isn't downloaded.
    // After the commit, not before it as manga does: a chapter a lane saves before the commit lands under the old
    // name, and this moves it. Not cancellable, since a later sync sees no change of name and would strand the file.
    withContext(NonCancellable) {
        downloadRenames.forEach { (old, new) -> novelDownloadManager?.renameChapter(novel, old, new) }
    }
    // The source no longer lists these, so their queued downloads could only fail, as the manga sync
    // cancels them too (RemovedChapterQueueConformanceTest).
    novelDownloadManager?.cancelDownloads(toDelete.map { it.id })

    // A page is compared with what that page held: chapters filling a page never stored are the back
    // catalogue, whatever other pages the novel has.
    val hadChapters = if (page == null) dbChapters.isNotEmpty() else dbChapters.any { it.page == page }
    return NovelChapterSyncResult(
        insertedChapters.filterNot { it.url in changedOrDuplicateReadUrls },
        changed = true,
        foundRelease(hadChapters, arrivals),
    )
}

/**
 * A plugin's chapter list as a sync stores it: one row per path, names without the novel's title in
 * front, numbers recognized from the whole list. Anything counting a plugin's list outside a sync
 * counts this, so its numbers match what a commit stores. [novelId] may be -1 for rows that are only
 * counted.
 */
fun List<ChapterItem>.toSourceChapters(novelId: Long, novelTitle: String, page: String? = null): List<NovelChapter> =
    numbered(novelTitle, toDrafts(novelId, novelTitle, page))

/** The rows a plugin's list becomes before numbering, each holding the number recognition starts from. */
private fun List<ChapterItem>.toDrafts(novelId: Long, novelTitle: String, page: String?): List<NovelChapter> =
    distinctBy { it.path }.mapIndexed { i, item ->
        val draft = item.toNovelChapter(novelId, sourceOrder = i.toLong())
        draft.copy(
            name = with(ChapterSanitizer) { draft.name.sanitize(novelTitle) },
            // A plugin that gives no number lands as 0.0, so only a positive one is the plugin's.
            rawChapterNumber = draft.chapterNumber.takeIf { it > 0.0 } ?: -1.0,
            // A paged sync stamps the transport index; otherwise keep the plugin's own page label.
            page = page ?: draft.page,
        )
    }

/** [chapters] numbered from the whole list: oldest first as plugins list them, reversed for the recognizer. */
private fun numbered(novelTitle: String, chapters: List<NovelChapter>): List<NovelChapter> =
    recognizeChapterNumbers(
        novelTitle,
        chapters.asReversed(),
        nameOf = { it.name },
        numberOf = { it.rawChapterNumber ?: it.chapterNumber },
        withNumber = { chapter, number -> chapter.copy(chapterNumber = number) },
    ).asReversed()

private fun NovelChapter.toStoredChapter(number: Double = chapterNumber) =
    StoredChapter(number, read, bookmark, dateFetch)
