package reikai.domain.novel.interactor

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.db.PassThroughTransactions
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.novel.download.NovelDownloadManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * What only the novel engine does. Every rule both engines share is pinned once, over both, by
 * MigrateEngineConformanceTest.
 */
class MigrateNovelUseCaseTest {

    private fun novel(id: Long) = Novel.create().copy(id = id)

    private fun useCase(
        novelChapterRepository: NovelChapterRepository,
        novelDownloadManager: NovelDownloadManager,
    ) = MigrateNovelUseCase(
        novelChapterRepository = novelChapterRepository,
        getNovelCategories = mockk(relaxed = true),
        setNovelCategories = mockk(relaxed = true),
        novelMergeManager = mockk<NovelMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(any()) } returns longArrayOf()
        },
        novelDownloadManagerProvider = { novelDownloadManager },
        updateNovel = mockk(relaxed = true),
        coverCache = mockk(relaxed = true),
        getNovelTracks = mockk(relaxed = true),
        insertNovelTrack = mockk(relaxed = true),
        sourceManager = mockk(relaxed = true),
        novelRepository = mockk<NovelRepository>(relaxed = true) { coEvery { updateAll(any()) } returns true },
        database = mockk(relaxed = true),
        libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        transactions = PassThroughTransactions,
        sourceTracker = mockk(relaxed = true),
        novelHistoryRepository = mockk(relaxed = true),
    )

    @Test
    fun `remove-download flag drops the whole source entry, not its downloaded chapters`() = runTest {
        val repo = mockk<NovelChapterRepository>(relaxed = true) {
            coEvery { getByNovelId(1L) } returns listOf(chapter(1, 1.0), chapter(2, 2.0))
            coEvery { getByNovelId(2L) } returns emptyList()
        }
        val downloadManager = mockk<NovelDownloadManager>(relaxed = true)

        useCase(novelChapterRepository = repo, novelDownloadManager = downloadManager)(
            novel(1),
            novel(2),
            setOf(NovelMigrationFlag.REMOVE_DOWNLOAD),
            replace = false,
            skipTargetRefresh = true,
        )

        // The whole entry, awaited. Per chapter it only reached what the disk cache already reported,
        // so anything still queued survived and kept downloading into the source just left behind.
        coVerify { downloadManager.awaitDeleteNovel(match { it.id == 1L }) }
        verify(exactly = 0) { downloadManager.deleteChapters(any()) }
    }

    private fun chapter(id: Long, number: Double, read: Boolean = false) = NovelChapter(
        id = id,
        novelId = 1L,
        url = "u$id",
        name = "Chapter $number",
        read = read,
        bookmark = false,
        lastTextProgress = 0,
        chapterNumber = number,
        sourceOrder = id,
        dateFetch = 0,
        dateUpload = 0,
        page = "",
    )

    @Test
    fun `a target chapter whose state already matches is not returned`() {
        // The novel carry writes only what changes, where manga's rewrites every target chapter.
        val current = listOf(chapter(1, 1.0, read = false))
        val target = listOf(chapter(10, 1.0, read = false))

        computeChapterMigration(current, target).shouldContainExactlyInAnyOrder(emptyList())
    }
}
