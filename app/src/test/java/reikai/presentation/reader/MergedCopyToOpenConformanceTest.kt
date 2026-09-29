package reikai.presentation.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.renderStoredStitch
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

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
    private val leadingChapters =
        listOf(chapter(13, leading, 7.0), chapter(12, leading, 6.0), chapter(11, leading, 5.0))
    private val otherChapters = listOf(chapter(23, other, 7.0), chapter(22, other, 6.0), chapter(21, other, 5.0))
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

    /** Opens the leading copy of chapter 6 and waits for the first chapter to land. */
    private suspend fun open(onDisk: Set<Long>, downloadedOnly: Boolean): ReaderViewModel.State {
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
            }
            val preferences = InMemoryPreferenceStore()
            val model = ReaderViewModel(
                savedState = SavedStateHandle(mapOf("manga" to leading.id, "chapter" to 12L)),
                context = mockk<Context>(relaxed = true),
                sourceManager = mockk(relaxed = true),
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
                getManga = mockk { coEvery { await(leading.id) } returns leading },
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
            ).also { store.put("reader", it) }
            // The model loads on the IO dispatcher, which virtual time does not reach.
            return withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    model.state.first { it.viewerChapters != null || it.initError != null }
                }
            }.also { it.initError?.let { error -> throw error } }
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
}
