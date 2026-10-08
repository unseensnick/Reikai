package reikai.presentation.recents

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import reikai.domain.download.MangaChapterDownloadActions
import reikai.domain.download.rowDownloadChapters
import reikai.domain.entry.EntryId
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.MergeScope
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

/**
 * Manga's chapter verbs on recent activity, keyed by chapter id: a read-lane row has no updates row to
 * look one up by. Moved off the updates model so a surface that never builds it still acts.
 */
@Inject
class MangaRecentsChapterActions(
    private val getChapter: GetChapter,
    private val setReadStatus: SetReadStatus,
    private val updateChapter: UpdateChapter,
    private val mergedChapterProvider: MergedChapterProvider,
    private val downloadActions: MangaChapterDownloadActions,
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
) : RecentsChapterActions {

    override suspend fun markRead(chapters: Set<ChapterRef>, read: Boolean) {
        withIOContext { setReadStatus.await(read, *chaptersOf(chapters.groupIds(MergeScope.Group)).toTypedArray()) }
    }

    // The already-at-this-value skip reads the stored chapter, the only copy a read-lane row has.
    override suspend fun setBookmark(chapters: Set<ChapterRef>, bookmarked: Boolean) {
        withIOContext {
            chaptersOf(chapters.groupIds(MergeScope.Group))
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(it.id) { bookmark = bookmarked } }
                .let { updateChapter.awaitAll(it) }
        }
    }

    override suspend fun download(chapters: Set<ChapterRef>, action: ChapterDownloadAction, scope: MergeScope) {
        val chapterIds = chapters.ownChapterIds<EntryId.Manga>()
        if (chapterIds.isEmpty()) return
        withIOContext {
            val rows = chaptersOf(chapterIds)
            val fetched =
                fetchedCopies(rows, scope, { it.id }, { it.mangaId }, mergedChapterProvider::stitchOf, ::chaptersOf) {
                    installedAmong(it)
                }
            downloadActions.run(action, rowDownloadChapters(action, rows, fetched) { it.id }) {
                chaptersOf(chapters.groupIds(scope))
            }
        }
    }

    /** The chapters of [chapters] whose own manga's source is installed. */
    private suspend fun installedAmong(chapters: List<Chapter>): Set<Long> {
        val installed = chapters.mapTo(HashSet()) { it.mangaId }.filterTo(HashSet()) { mangaId ->
            getManga.await(mangaId)?.let { sourceManager.getOrStub(it.source) !is StubSource } == true
        }
        return chapters.filter { it.mangaId in installed }.mapTo(HashSet()) { it.id }
    }

    override suspend fun deleteDownloads(chapters: Set<ChapterRef>, scope: MergeScope) {
        withIOContext { downloadActions.delete(chaptersOf(chapters.groupIds(scope))) }
    }

    private suspend fun Set<ChapterRef>.groupIds(scope: MergeScope) =
        groupChapterIds<EntryId.Manga>(scope, mergedChapterProvider::stitchOf)

    private suspend fun chaptersOf(chapterIds: List<Long>): List<Chapter> = chapterIds.mapNotNull {
        getChapter.await(it)
    }
}
