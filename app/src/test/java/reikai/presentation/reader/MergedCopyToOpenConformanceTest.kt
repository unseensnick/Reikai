package reikai.presentation.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.unmockkConstructor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.download.MangaChapterDownloadActions
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.renderStoredStitch
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

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

    // Manga only: the novel manager re-queues a failed chapter on enqueue (ChapterDownloadActionsConformanceTest).
    @org.junit.jupiter.api.Test
    fun `manga retry from the sheet starts the stopped downloads again`() = runTest {
        MangaCopyProbe().sheetRetryRestartsDownloads() shouldBe true
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
 * A real [ReaderViewModel] over stubbed interactors. The page loader is the network, so it is stubbed
 * too, and the loaded chapter is read off the model's state.
 */
class MangaCopyProbe : MergedCopyProbe {

    override fun toString() = "manga"

    private val leading = Manga.create().copy(id = 1L, source = 100L, title = "Leading")
    private val other = Manga.create().copy(id = 2L, source = 200L, title = "Other")

    private fun chapter(id: Long, manga: Manga, number: Double) =
        Chapter.create().copy(id = id, mangaId = manga.id, chapterNumber = number, name = "$id", url = "/$id")

    // Newest first, as a manga source lists them.
    private val leadingChapters = listOf(
        chapter(14, leading, 8.0),
        chapter(13, leading, 7.0),
        chapter(12, leading, 6.0),
        chapter(11, leading, 5.0),
    )
    private val otherChapters =
        listOf(chapter(24, other, 8.0), chapter(23, other, 7.0), chapter(22, other, 6.0), chapter(21, other, 5.0))
    private val stitch = leadingChapters.indices.flatMap { unit ->
        listOf(
            ChapterUnit(chapterId = leadingChapters[unit].id, unit = unit, copyOrder = 0),
            ChapterUnit(chapterId = otherChapters[unit].id, unit = unit, copyOrder = 1),
        )
    }

    override suspend fun opensTheCopyOnDisk(scope: TestScope): Boolean {
        val state = open(onDisk = setOf(22L), downloadedOnly = false)
        return state.viewerChapters?.currChapter?.chapter?.id == 22L
    }

    override suspend fun nextIsTheCopyOnDisk(scope: TestScope, downloadedOnly: Boolean): Boolean {
        val state = open(onDisk = setOf(23L), downloadedOnly = downloadedOnly)
        return state.viewerChapters?.nextChapter?.chapter?.id == 23L
    }

    override suspend fun sheetShowsDownloaded(scope: TestScope, sourceScoped: Boolean): Boolean =
        open(onDisk = setOf(22L), downloadedOnly = false, sourceScoped = sourceScoped) { model, _ ->
            val rows = if (sourceScoped) {
                leadingChapters
            } else {
                renderStoredStitch(
                    leadingChapters + otherChapters,
                    stitch,
                ) {
                    it.id
                }
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
        ) { model, state ->
            val current = state.viewerChapters!!.currChapter
            val pages = listOf(ReaderPage(0), ReaderPage(1)).onEach { it.chapter = current }
            current.state = ReaderChapter.State.Loaded(pages)
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

    /** Chapter 6's download failed; tapping its indicator on the sheet raises START. */
    suspend fun sheetRetryRestartsDownloads(): Boolean {
        val restarted = java.util.concurrent.atomic.AtomicBoolean(false)
        return open(
            onDisk = emptySet(),
            downloadedOnly = false,
            sourceScoped = false,
            failed = setOf(12L),
            onRestart = { restarted.set(true) },
        ) { model, _ ->
            model.handleChapterDownload(leadingChapters.first { it.id == 12L }, ChapterDownloadAction.START)
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(3_000) { while (!restarted.get()) delay(20) }
            }
            restarted.get()
        }
    }

    override suspend fun sheetDeleteReachesSibling(scope: TestScope, sourceScoped: Boolean): Boolean {
        val deleted = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        return open(
            onDisk = setOf(12L, 22L),
            downloadedOnly = false,
            sourceScoped = sourceScoped,
            onDelete = { chapters -> chapters.forEach { deleted += it.id } },
        ) { model, _ ->
            model.handleChapterDownload(leadingChapters.first { it.id == 12L }, ChapterDownloadAction.DELETE)
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(3_000) { while (12L !in deleted) delay(20) }
                delay(200)
            }
            22L in deleted
        }
    }

    private suspend fun open(onDisk: Set<Long>, downloadedOnly: Boolean): ReaderViewModel.State =
        open(onDisk, downloadedOnly, sourceScoped = false) { _, state -> state }

    /** Opens the leading copy of chapter 6, waits for the first chapter to land, then asks [probe]. */
    private suspend fun <T> open(
        onDisk: Set<Long>,
        downloadedOnly: Boolean,
        sourceScoped: Boolean,
        downloadAhead: Int = 0,
        onDownload: (List<Chapter>) -> Unit = {},
        onDelete: (List<Chapter>) -> Unit = {},
        failed: Set<Long> = emptySet(),
        onRestart: () -> Unit = {},
        probe: suspend (ReaderViewModel, ReaderViewModel.State) -> T,
    ): T {
        // The model starts loading from its init block, which a main dispatcher nothing advances never runs.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkConstructor(ChapterLoader::class)
        coEvery { anyConstructed<ChapterLoader>().loadChapter(any(), any()) } just runs
        val store = ViewModelStore()
        try {
            val pooled = leadingChapters + otherChapters
            val shown = renderStoredStitch(pooled, stitch) { it.id }
                .mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) }
            val group = MergedChapterProvider.Group(
                mangaById = mapOf(leading.id to leading, other.id to other),
                chapters = shown,
                sourceNameByMangaId = mapOf(leading.id to "Leading", other.id to "Other"),
                stitch = stitch,
                pooledChapters = pooled,
            )
            val byTitle = mapOf(leading.title to leadingChapters, other.title to otherChapters)
            val downloadManager = mockk<DownloadManager>(relaxed = true) {
                every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers {
                    val chapterName = firstArg<String>()
                    byTitle[arg<String>(3)].orEmpty().any { it.name == chapterName && it.id in onDisk }
                }
                coEvery { downloadChapters(any(), any(), any()) } answers { onDownload(secondArg()) }
                every { deleteChapters(any(), any(), any()) } answers { onDelete(firstArg()) }
                every { getQueuedDownloadOrNull(any()) } answers {
                    pooled.find { it.id == firstArg<Long>() && it.id in failed }
                        ?.let { Download(mockk(), leading, it).apply { status = Download.State.ERROR } }
                }
                every { startDownloads() } answers { onRestart() }
            }
            val getManga = mockk<GetManga> {
                coEvery { await(leading.id) } returns leading
                coEvery { await(other.id) } returns other
            }
            val sourceManager = mockk<SourceManager>(relaxed = true)
            val preferences = InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreferenceStore.InMemoryPreference("auto_download_while_reading", downloadAhead, 0),
                ),
            )
            val model = ReaderViewModel(
                savedState = SavedStateHandle(
                    mapOf("manga" to leading.id, "chapter" to 12L, "source_scoped" to sourceScoped),
                ),
                context = mockk<Context>(relaxed = true),
                sourceManager = sourceManager,
                downloadManager = downloadManager,
                downloadProvider = mockk(relaxed = true),
                imageSaver = mockk(relaxed = true),
                readerPreferences = ReaderPreferences(preferences),
                basePreferences = mockk<BasePreferences>().also { base ->
                    every { base.downloadedOnly } returns mockk<Preference<Boolean>> {
                        every { get() } returns downloadedOnly
                    }
                },
                downloadPreferences = DownloadPreferences(preferences),
                trackPreferences = TrackPreferences(preferences),
                trackChapter = mockk(relaxed = true),
                sourceTracker = mockk(relaxed = true),
                getManga = getManga,
                getCustomMangaInfo = mockk { every { subscribe(any()) } returns flowOf(null) },
                getChaptersByMangaId = mockk {
                    coEvery { await(leading.id, any()) } returns leadingChapters
                    coEvery { await(other.id, any()) } returns otherChapters
                },
                getNextChapters = mockk(relaxed = true),
                upsertHistory = mockk(relaxed = true),
                updateChapter = mockk(relaxed = true),
                setMangaViewerFlags = mockk(relaxed = true),
                getIncognitoState = mockk(relaxed = true),
                libraryPreferences = LibraryPreferences(preferences),
                coverManager = mockk(relaxed = true),
                updateManga = mockk(relaxed = true),
                coverCache = mockk(relaxed = true),
                chapterCache = mockk(relaxed = true),
                downloadCache = mockk(relaxed = true) {
                    every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers {
                        val chapterName = firstArg<String>()
                        byTitle[arg<String>(3)].orEmpty().any { it.name == chapterName && it.id in onDisk }
                    }
                },
                mergedChapterProvider = mockk { coEvery { load(leading) } returns group },
                mangaPreferences = MangaPreferences(preferences),
                setReadStatus = mockk(relaxed = true),
                getChapter = mockk(relaxed = true),
                chapterDownloadActions = MangaChapterDownloadActions(downloadManager, getManga, sourceManager),
            ).also { store.put("reader", it) }
            // The model loads on the IO dispatcher, which virtual time does not reach.
            val state = withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    model.state.first { it.viewerChapters != null || it.initError != null }
                }
            }.also { it.initError?.let { error -> throw error } }
            return probe(model, state)
        } finally {
            store.clear()
            unmockkConstructor(ChapterLoader::class)
        }
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
