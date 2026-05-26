package eu.kanade.tachiyomi.util.chapter

import java.util.Date
import java.util.TreeSet
import yokai.data.DatabaseHandler
import yokai.data.novel.toNovelChapter
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.models.Novel
import yokai.domain.novel.models.NovelChapter
import yokai.novel.host.ChapterItem
import yokai.novel.source.NovelSource

/**
 * Novel-side parallel of [syncChaptersWithSource]. Mirrors the manga helper's diff / insert /
 * update / delete pipeline against `novel_chapters`, stripped of manga-only concerns:
 *
 * * No scanlator filtering (Phase 7 Decision #1: novels have no scanlators).
 * * No download-manager interaction (Decision #4: novel downloads stubbed).
 * * No chapter-number recognition heuristic (lnreader plugins return `ChapterItem.chapterNumber`
 *   as a Double already; no name-parsing needed).
 * * No `markDuplicateReadChapterAsRead` library-pref branch (manga-only feature; deferred for
 *   novels until the Novels tab surfaces a preference for it).
 *
 * The `source` parameter is currently unused but kept on the signature so the call site reads
 * like the manga helper and so future per-source post-processing (an [NovelSource] analogue of
 * `HttpSource.prepareNewChapter`) has a place to land without a signature change.
 *
 * @return pair of (newly inserted chapters, deleted chapters), each filtered to exclude entries
 *   whose only change was a duplicate-read marking — matching manga semantics so the caller's
 *   "new chapters" notification doesn't fire on reorders.
 */
suspend fun syncChaptersWithNovelSource(
    rawSourceChapters: List<ChapterItem>,
    novel: Novel,
    @Suppress("UNUSED_PARAMETER") source: NovelSource,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    handler: DatabaseHandler,
): Pair<List<NovelChapter>, List<NovelChapter>> {
    if (rawSourceChapters.isEmpty()) {
        throw Exception("No chapters found")
    }

    val novelId = novel.id
        ?: throw IllegalArgumentException("syncChaptersWithNovelSource requires a persisted novel (id != null)")

    val dbChapters = novelChapterRepository.getByNovelId(novelId)

    val sourceChapters = rawSourceChapters
        .distinctBy { it.path }
        .mapIndexed { i, item ->
            val draft = item.toNovelChapter(novelId, sourceOrder = i.toLong())
            draft.copy(name = with(ChapterSanitizer) { draft.name.sanitize(novel.title) })
        }

    val toAdd = mutableListOf<NovelChapter>()
    // Full NovelChapter copies (with id set) for update via novel_chaptersQueries.update.
    val toChange = mutableListOf<NovelChapter>()

    val duplicates = dbChapters.groupBy { it.url }
        .filter { it.value.size > 1 }
        .flatMap { (_, chapters) -> chapters.drop(1) }
    val notInSource = dbChapters.filterNot { dbChapter ->
        sourceChapters.any { it.url == dbChapter.url }
    }
    val toDelete = duplicates + notInSource

    val managedUrls = mutableSetOf<String>()

    for (sourceChapter in sourceChapters) {
        if (sourceChapter.url in managedUrls) continue
        managedUrls += sourceChapter.url

        val dbChapter = dbChapters.find { it.url == sourceChapter.url }
        if (dbChapter == null) {
            toAdd.add(sourceChapter)
        } else if (shouldUpdateDbNovelChapter(dbChapter, sourceChapter)) {
            toChange.add(
                dbChapter.copy(
                    name = sourceChapter.name,
                    dateUpload = sourceChapter.dateUpload,
                    chapterNumber = sourceChapter.chapterNumber,
                    sourceOrder = sourceChapter.sourceOrder,
                    page = sourceChapter.page,
                ),
            )
        }
    }

    if (toAdd.isEmpty() && toDelete.isEmpty() && toChange.isEmpty()) {
        return Pair(emptyList(), emptyList())
    }

    val changedOrDuplicateReadUrls = mutableSetOf<String>()

    val deletedChapterNumbers = TreeSet<Float>()
    val deletedReadChapterNumbers = TreeSet<Float>()
    val deletedBookmarkedChapterNumbers = TreeSet<Float>()

    toDelete.forEach {
        if (it.read) deletedReadChapterNumbers.add(it.chapterNumber)
        if (it.bookmark) deletedBookmarkedChapterNumbers.add(it.chapterNumber)
        deletedChapterNumbers.add(it.chapterNumber)
    }

    val now = Date().time

    // Date fetch is staggered so newer-listed chapters get higher values. Sources MUST return
    // chapters from most to least recent (mirrors manga).
    var itemCount = toAdd.size
    val updatedToAdd = toAdd.map { addItem ->
        var dateFetch = now + itemCount--
        var read = addItem.read
        var bookmark = addItem.bookmark

        if (addItem.chapterNumber >= 0f && addItem.chapterNumber in deletedChapterNumbers) {
            read = addItem.chapterNumber in deletedReadChapterNumbers
            bookmark = addItem.chapterNumber in deletedBookmarkedChapterNumbers
            // Reuse the original fetch date so this re-added chapter doesn't bubble up in 'Updates'.
            toDelete.filter { it.chapterNumber == addItem.chapterNumber }
                .minByOrNull { it.dateFetch }
                ?.let { dateFetch = it.dateFetch }
            changedOrDuplicateReadUrls.add(addItem.url)
        }

        addItem.copy(dateFetch = dateFetch, read = read, bookmark = bookmark)
    }

    val insertedChapters = mutableListOf<NovelChapter>()

    handler.await(inTransaction = true) {
        toDelete.forEach { ch -> ch.id?.let { novel_chaptersQueries.delete(it) } }

        for (chapter in updatedToAdd) {
            novel_chaptersQueries.insert(
                novelId = chapter.novelId,
                url = chapter.url,
                name = chapter.name,
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastTextProgress = chapter.lastTextProgress.toLong(),
                chapterNumber = chapter.chapterNumber.toDouble(),
                sourceOrder = chapter.sourceOrder,
                dateFetch = chapter.dateFetch,
                dateUpload = chapter.dateUpload,
                page = chapter.page,
            )
            val insertedId = novel_chaptersQueries.selectLastInsertedRowId().executeAsOne()
            insertedChapters += chapter.copy(id = insertedId)
        }

        for (chapter in toChange) {
            val chapterId = chapter.id ?: continue
            novel_chaptersQueries.update(
                novelId = null,
                url = null,
                name = chapter.name,
                read = null,
                bookmark = null,
                lastTextProgress = null,
                chapterNumber = chapter.chapterNumber.toDouble(),
                sourceOrder = chapter.sourceOrder,
                dateFetch = null,
                dateUpload = chapter.dateUpload,
                page = chapter.page,
                chapterId = chapterId,
            )
        }

        // Catch the case where the only change is reorder (chapter passed shouldUpdate but is
        // also a no-op for our toChange list because dateUpload / name didn't move). Manga
        // does this same belt-and-braces fix via chaptersQueries.fixSourceOrder.
        sourceChapters.forEach { chapter ->
            novel_chaptersQueries.fixSourceOrder(
                url = chapter.url,
                novelId = chapter.novelId,
                sourceOrder = chapter.sourceOrder,
            )
        }
    }

    // novels.last_update tracks the last time the chapter list changed at all (any of add /
    // delete / change). Only writes here, never on a no-op.
    novelRepository.update(novel.copy(lastUpdate = Date().time))

    return Pair(
        insertedChapters.filterNot { it.url in changedOrDuplicateReadUrls },
        toDelete.filterNot { it.url in changedOrDuplicateReadUrls },
    )
}

private fun shouldUpdateDbNovelChapter(dbChapter: NovelChapter, sourceChapter: NovelChapter): Boolean {
    return dbChapter.name != sourceChapter.name ||
        dbChapter.dateUpload != sourceChapter.dateUpload ||
        dbChapter.chapterNumber != sourceChapter.chapterNumber ||
        dbChapter.sourceOrder != sourceChapter.sourceOrder ||
        dbChapter.page != sourceChapter.page
}
