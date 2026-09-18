package reikai.presentation.reader

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter

/**
 * What a chapter that would not load leaves the reader with, pinned once over both providers: whether
 * the reader stays open depends only on whether a chapter is on screen, and retrying a reload from the
 * source goes back to the source rather than to a downloaded copy.
 */
class ReaderLoadFailureConformanceTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a failed step with a chapter on screen keeps the reader open`(probe: ReaderLoadFailureProbe) = runTest {
        val state = probe.failedLoad(this, chapterOnScreen = true)

        (state as? ReaderLoadState.Failed)?.canKeepReading shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a chapter that fails with nothing on screen closes the reader`(probe: ReaderLoadFailureProbe) = runTest {
        val state = probe.failedLoad(this, chapterOnScreen = false)

        (state as? ReaderLoadState.Failed)?.canKeepReading shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `retrying a failed reload from the source reloads from the source`(probe: ReaderLoadFailureProbe) =
        runTest {
            probe.retryOfFailedSourceReloadReadsTheSource(this) shouldBe true
        }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaLoadFailureProbe(), NovelLoadFailureProbe())
    }
}

/** One content type's provider, taken to a failed load. */
interface ReaderLoadFailureProbe {

    /** The state after a chapter fails to load, with or without [chapterOnScreen] already showing. */
    suspend fun failedLoad(scope: TestScope, chapterOnScreen: Boolean): ReaderLoadState

    /** A reload from the source that failed, then retried: whether the retry read the source. */
    suspend fun retryOfFailedSourceReloadReadsTheSource(scope: TestScope): Boolean
}

/** Manga's failure is upstream's model state, so the provider is driven over a stubbed model. */
class MangaLoadFailureProbe : ReaderLoadFailureProbe {

    override fun toString() = "manga"

    private fun provider(state: ReaderViewModel.State, viewModel: ReaderViewModel = mockk(relaxed = true)) =
        MangaReaderProvider(
            viewModel = viewModel.also { every { it.state } returns MutableStateFlow(state) },
            readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
            downloadManager = mockk(relaxed = true),
            titleWords = EnglishChapterTitleWords,
        )

    override suspend fun failedLoad(scope: TestScope, chapterOnScreen: Boolean): ReaderLoadState {
        val onScreen = ReaderChapter(Chapter.create().copy(id = 4L).toDbChapter())
        val state = ReaderViewModel.State(
            viewerChapters = ViewerChapters(onScreen, prevChapter = null, nextChapter = null)
                .takeIf { chapterOnScreen },
            adjacentLoadFailure = ReaderViewModel.AdjacentLoadFailure(5L, "no connection", false, 1L),
        )
        return provider(state).loadState.first()
    }

    override suspend fun retryOfFailedSourceReloadReadsTheSource(scope: TestScope): Boolean {
        val asked = mutableListOf<Boolean>()
        val viewModel = mockk<ReaderViewModel>(relaxed = true) {
            every { reloadChapter(any()) } answers { asked += firstArg<Boolean>() }
        }
        val state = ReaderViewModel.State(
            adjacentLoadFailure = ReaderViewModel.AdjacentLoadFailure(5L, null, true, 1L),
        )

        provider(state, viewModel).retryLoad()

        return asked == listOf(true)
    }
}

/** Novel's failure is the model's own, so a real one is driven over the harness. */
class NovelLoadFailureProbe : ReaderLoadFailureProbe {

    override fun toString() = "novel"

    private fun provider(harness: NovelReaderViewModelHarness, model: NovelReaderViewModel) =
        NovelReaderProvider(
            viewModel = model,
            novelPreferences = harness.novelPreferences,
            fontManager = mockk(),
            titleWords = EnglishChapterTitleWords,
        )

    override suspend fun failedLoad(scope: TestScope, chapterOnScreen: Boolean): ReaderLoadState =
        NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
            val source = harness.source("src")
            val novel = harness.novel(source)
            val first = harness.chapter(novel, 1.0)
            val failing = harness.chapter(novel, 2.0).also { source.failing += it.url }
            val model = harness.open(novel, if (chapterOnScreen) first.id else failing.id)
            scope.advanceUntilIdle()

            if (chapterOnScreen) model.nextChapter()
            scope.advanceUntilIdle()

            provider(harness, model).loadState.first()
        }

    override suspend fun retryOfFailedSourceReloadReadsTheSource(scope: TestScope): Boolean =
        NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
            val source = harness.source("src")
            val novel = harness.novel(source)
            val chapter = harness.chapter(novel, 1.0).also { harness.download(it, "<p>From the download</p>") }
            val provider = provider(harness, harness.open(novel, chapter.id))
            scope.advanceUntilIdle()
            source.failing += chapter.url
            provider.reloadChapter(fromSource = true)
            scope.advanceUntilIdle()
            source.failing.clear()

            provider.retryLoad()
            scope.advanceUntilIdle()

            provider.viewModel.chapter.value?.html.orEmpty().contains("From the source")
        }
}
