package reikai.presentation.reader

import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.renderStoredStitch
import tachiyomi.domain.chapter.model.Chapter

/**
 * Which copy of a merged chapter the reader reads, pinned once over both readers. Each group has two
 * sources, the leading one never on disk here: opening, stepping and Downloaded only must all reach
 * the other source's copy on disk rather than the leading copy online.
 */
class MergedCopyToOpenConformanceTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `opening a chapter reads another source's copy on disk`(probe: MergedCopyProbe) = runTest {
        probe.opensTheCopyOnDisk(this) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the next chapter is another source's copy on disk`(probe: MergedCopyProbe) = runTest {
        probe.nextIsTheCopyOnDisk(this, downloadedOnly = false) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `with Downloaded only the next chapter is another source's copy on disk`(probe: MergedCopyProbe) =
        runTest {
            probe.nextIsTheCopyOnDisk(this, downloadedOnly = true) shouldBe true
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `in source scope the sheet does not count another source's copy as downloaded`(probe: MergedCopyProbe) =
        runTest {
            probe.sheetShowsDownloaded(this, sourceScoped = true) shouldBe false
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `in group scope the sheet counts another source's copy as downloaded`(probe: MergedCopyProbe) = runTest {
        probe.sheetShowsDownloaded(this, sourceScoped = false) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `in source scope a sheet delete leaves another source's copy`(probe: MergedCopyProbe) = runTest {
        probe.sheetDeleteReachesSibling(this, sourceScoped = true) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `in group scope a sheet delete removes another source's copy too`(probe: MergedCopyProbe) = runTest {
        probe.sheetDeleteReachesSibling(this, sourceScoped = false) shouldBe true
    }

    // Manga only: the novel reader already walks the list it pages (NovelReaderViewModel.maybeDownloadAhead).
    @org.junit.jupiter.api.Test
    fun `manga download-ahead queues the copy the reader will open`() = runTest {
        MangaCopyProbe().downloadAheadQueues() shouldBe setOf(13L, 24L)
    }

    // The next chapter is itself another source's copy on disk (23): the walk still starts from it.
    @org.junit.jupiter.api.Test
    fun `manga download-ahead walks on from a next chapter that is another source's copy`() = runTest {
        MangaCopyProbe().downloadAheadQueues(onDisk = setOf(22L, 23L, 24L)) shouldBe setOf(23L, 24L)
    }

    // What queueing does with a failed chapter is the engines' rule (ChapterDownloadActionsConformanceTest).
    @org.junit.jupiter.api.Test
    fun `manga sheet download queues the row's own chapter`() = runTest {
        MangaCopyProbe().sheetStartQueues() shouldBe setOf(12L)
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaCopyProbe(), NovelCopyProbe())
    }
}

/** One content type's reader, opened on a merged chapter whose leading copy is not on disk. */
interface MergedCopyProbe {

    /** Chapter 6 has only the second source's copy on disk: whether opening the leading copy reads it. */
    suspend fun opensTheCopyOnDisk(scope: TestScope): Boolean

    /** Chapter 7 has only the second source's copy on disk, chapter 6 none: whether the step from the
     *  leading copy of chapter 6 lands on it. */
    suspend fun nextIsTheCopyOnDisk(scope: TestScope, downloadedOnly: Boolean): Boolean

    /** Chapter 6 has only the second source's copy on disk: whether the chapter sheet, opened on the
     *  leading copy in [sourceScoped], shows that row as downloaded. */
    suspend fun sheetShowsDownloaded(scope: TestScope, sourceScoped: Boolean): Boolean

    /** Deleting chapter 6's download from the sheet, opened on the leading copy in [sourceScoped]:
     *  whether the delete reaches the second source's copy. */
    suspend fun sheetDeleteReachesSibling(scope: TestScope, sourceScoped: Boolean): Boolean
}

/**
 * A real [ReaderViewModel] over [MangaReaderViewModelHarness]. Two sources carry chapters 5 to 8, newest
 * first as a manga source lists them, stitched unit by unit, and the leading copy of chapter 6 is opened.
 */
class MangaCopyProbe : MergedCopyProbe {

    override fun toString() = "manga"

    override suspend fun opensTheCopyOnDisk(scope: TestScope): Boolean {
        val state = open(onDisk = setOf(22L), downloadedOnly = false)
        return state.viewerChapters?.currChapter?.chapter?.id == 22L
    }

    override suspend fun nextIsTheCopyOnDisk(scope: TestScope, downloadedOnly: Boolean): Boolean {
        val state = open(onDisk = setOf(23L), downloadedOnly = downloadedOnly)
        return state.viewerChapters?.nextChapter?.chapter?.id == 23L
    }

    override suspend fun sheetShowsDownloaded(scope: TestScope, sourceScoped: Boolean): Boolean =
        open(onDisk = setOf(22L), downloadedOnly = false, sourceScoped = sourceScoped) { model, _, group ->
            val rows = if (sourceScoped) {
                group.pooledChapters.filter { it.mangaId == LEADING }
            } else {
                renderStoredStitch(group.pooledChapters, group.stitch) { it.id }
            }
            val row = rows.first { it.chapterNumber == 6.0 }
            model.sheetFlags(rows).isDownloaded(row)
        }

    /**
     * Chapter 6 opens as the second source's copy on disk, chapter 7's leading copy is on disk, and
     * chapter 8 only as the second source's copy. Reading into chapter 6 queues download-ahead: the ids
     * it hands the download manager. Chapter 8 must be the copy on disk (24), the one the reader pages
     * to, never the leading copy (14) it would fetch again.
     */
    suspend fun downloadAheadQueues(onDisk: Set<Long> = setOf(22L, 13L, 24L)): Set<Long> {
        val queued = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        return open(
            onDisk = onDisk,
            downloadedOnly = false,
            sourceScoped = false,
            downloadAhead = 2,
            onDownload = { chapters -> chapters.forEach { queued += it.id } },
        ) { model, state, _ ->
            val current = state.viewerChapters!!.currChapter
            val pages = loadedPages(current, 2)
            // Download-ahead only runs while the chapter being read is itself read from disk.
            current.pageLoader = mockk<DownloadPageLoader>(relaxed = true)
            model.onPageSelected(pages[1])
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(3_000) { while (queued.isEmpty()) delay(20) }
                delay(300)
            }
            queued.toSet()
        }
    }

    /** Tapping chapter 6's download indicator on the sheet raises START: the ids handed to the downloader. */
    suspend fun sheetStartQueues(): Set<Long> {
        val queued = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        return open(
            onDisk = emptySet(),
            downloadedOnly = false,
            sourceScoped = false,
            onDownload = { chapters -> chapters.forEach { queued += it.id } },
        ) { model, _, group ->
            model.handleChapterDownload(group.pooledChapters.first { it.id == 12L }, ChapterDownloadAction.START)
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(3_000) { while (queued.isEmpty()) delay(20) }
            }
            queued.toSet()
        }
    }

    override suspend fun sheetDeleteReachesSibling(scope: TestScope, sourceScoped: Boolean): Boolean {
        val deleted = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        return open(
            onDisk = setOf(12L, 22L),
            downloadedOnly = false,
            sourceScoped = sourceScoped,
            onDelete = { chapters -> chapters.forEach { deleted += it.id } },
        ) { model, _, group ->
            model.handleChapterDownload(group.pooledChapters.first { it.id == 12L }, ChapterDownloadAction.DELETE)
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(3_000) { while (12L !in deleted) delay(20) }
                delay(200)
            }
            22L in deleted
        }
    }

    private suspend fun open(onDisk: Set<Long>, downloadedOnly: Boolean): ReaderViewModel.State =
        open(onDisk, downloadedOnly, sourceScoped = false) { _, state, _ -> state }

    /** Seeds both sources, opens the leading copy of chapter 6, then asks [probe]. */
    private suspend fun <T> open(
        onDisk: Set<Long>,
        downloadedOnly: Boolean,
        sourceScoped: Boolean,
        downloadAhead: Int = 0,
        onDownload: (List<Chapter>) -> Unit = {},
        onDelete: (List<Chapter>) -> Unit = {},
        probe: suspend (ReaderViewModel, ReaderViewModel.State, MergedChapterProvider.Group) -> T,
    ): T = MangaReaderViewModelHarness.create().use { harness ->
        val leading = harness.manga(LEADING, source = 100L, title = "Leading")
        val other = harness.manga(OTHER, source = 200L, title = "Other")
        val numbers = listOf(8.0, 7.0, 6.0, 5.0)
        val leadingChapters = listOf(14L, 13L, 12L, 11L).zip(numbers) { id, number ->
            harness.chapter(id, leading, number)
        }
        val otherChapters = listOf(24L, 23L, 22L, 21L).zip(numbers) { id, number -> harness.chapter(id, other, number) }
        val stitch = leadingChapters.indices.flatMap { unit ->
            listOf(
                ChapterUnit(chapterId = leadingChapters[unit].id, unit = unit, copyOrder = 0),
                ChapterUnit(chapterId = otherChapters[unit].id, unit = unit, copyOrder = 1),
            )
        }
        val pooled = leadingChapters + otherChapters
        val group = MergedChapterProvider.Group(
            mangaById = mapOf(leading.id to leading, other.id to other),
            chapters = renderStoredStitch(pooled, stitch) { it.id }
                .mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) },
            sourceNameByMangaId = mapOf(leading.id to "Leading", other.id to "Other"),
            stitch = stitch,
            pooledChapters = pooled,
        )
        harness.open(
            leading,
            chapterId = 12L,
            group = group,
            preferences = mapOf("auto_download_while_reading" to downloadAhead),
            onDisk = onDisk,
            downloadedOnly = downloadedOnly,
            sourceScoped = sourceScoped,
            onDownload = onDownload,
            onDelete = onDelete,
        ) { model, state -> probe(model, state, group) }
    }

    private companion object {
        const val LEADING = 1L
        const val OTHER = 2L
    }
}

/** A real [NovelReaderViewModel] over [NovelReaderViewModelHarness]. */
class NovelCopyProbe : MergedCopyProbe {

    override fun toString() = "novel"

    override suspend fun opensTheCopyOnDisk(scope: TestScope): Boolean =
        NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
            val leading = harness.novel(harness.source("alpha"))
            val other = harness.novel(harness.source("beta"))
            val opened = harness.chapter(leading, 6.0)
            harness.download(harness.chapter(other, 6.0), "<p>The other source's copy on disk</p>")
            harness.merge(leading, other)
            val model = harness.open(leading, opened.id)
            scope.advanceUntilIdle()

            model.chapter.value?.html.orEmpty().contains("The other source's copy on disk")
        }

    override suspend fun nextIsTheCopyOnDisk(scope: TestScope, downloadedOnly: Boolean): Boolean =
        NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
            val leading = harness.novel(harness.source("alpha"))
            val other = harness.novel(harness.source("beta"))
            val opened = harness.chapter(leading, 6.0)
            harness.chapter(leading, 7.0)
            harness.chapter(other, 6.0)
            val onDisk = harness.chapter(other, 7.0).also { harness.download(it, "<p>Seven on disk</p>") }
            harness.merge(leading, other)
            harness.downloadedOnly.set(downloadedOnly)
            val model = harness.open(leading, opened.id)
            scope.advanceUntilIdle()

            model.chapterNeighbours.value.next == onDisk.id
        }

    override suspend fun sheetShowsDownloaded(scope: TestScope, sourceScoped: Boolean): Boolean =
        NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
            val leading = harness.novel(harness.source("alpha"))
            val other = harness.novel(harness.source("beta"))
            val opened = harness.chapter(leading, 6.0)
            harness.download(harness.chapter(other, 6.0), "<p>The other source's copy on disk</p>")
            harness.merge(leading, other)
            val model = harness.open(leading, opened.id, sourceScoped = sourceScoped)
            scope.advanceUntilIdle()

            // In group scope the opened row is swapped for the copy on disk; the sheet shows chapter 6 once.
            model.chapterRows.first().single().downloadState == Download.State.DOWNLOADED
        }

    override suspend fun sheetDeleteReachesSibling(scope: TestScope, sourceScoped: Boolean): Boolean =
        NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
            val leading = harness.novel(harness.source("alpha"))
            val other = harness.novel(harness.source("beta"))
            val opened = harness.chapter(leading, 6.0)
            val sibling = harness.chapter(other, 6.0)
            harness.merge(leading, other)
            val deleted = mutableSetOf<Long>()
            every { harness.downloadManager.deleteChapters(any()) } answers {
                deleted += firstArg<List<reikai.domain.novel.model.NovelChapter>>().map { it.id }
            }
            val model = harness.open(leading, opened.id, sourceScoped = sourceScoped)
            scope.advanceUntilIdle()

            model.downloadChapter(opened.id, ChapterDownloadAction.DELETE)
            scope.advanceUntilIdle()
            sibling.id in deleted
        }
}
