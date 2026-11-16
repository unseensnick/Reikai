package reikai.presentation.reader

import io.kotest.matchers.collections.shouldContain
import kotlinx.coroutines.Dispatchers
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

/**
 * A step or a chapter-sheet pick stamps the chapter it leaves into history, with the time read in it,
 * before the next one starts its own session. Pinned once over both readers.
 */
class ReaderStepHistoryConformanceTest {

    interface Probe {
        /** Opens chapter 1 of two, moves to chapter 2 by a Next step or by a [pick], and answers History. */
        suspend fun historyAfterMove(scope: TestScope, pick: Boolean): List<Long>
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a Next step puts the chapter it leaves in history`(probe: Probe) = runTest {
        probe.historyAfterMove(this, pick = false) shouldContain FIRST
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a pick from the chapter sheet puts the chapter it leaves in history`(probe: Probe) = runTest {
        probe.historyAfterMove(this, pick = true) shouldContain FIRST
    }

    companion object {
        /** The chapter both probes open on, under the id each one stores it with. */
        const val FIRST = -1L

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun historyAfterMove(scope: TestScope, pick: Boolean): List<Long> =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                val second = harness.chapter(20L, manga, 2.0)
                harness.open(manga, chapterId = 10L) { model, _ ->
                    model.restartReadTimer()
                    if (pick) {
                        model.loadNewChapterFromDialog(second)
                        settled(model) { it.viewerChapters?.currChapter?.chapter?.id == 20L }
                    } else {
                        model.loadNextChapter()
                    }
                    harness.historyOf(manga.id).map { if (it == 10L) FIRST else it }
                }
            }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun historyAfterMove(scope: TestScope, pick: Boolean): List<Long> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val source = harness.source("src")
                val novel = harness.novel(source)
                val first = harness.chapter(novel, 1.0)
                val second = harness.chapter(novel, 2.0)
                val model = harness.open(novel, first.id)
                scope.advanceUntilIdle()

                if (pick) model.open(second.id) else model.nextChapter()
                scope.advanceUntilIdle()

                harness.historyOf(novel).map { if (it == first.id) FIRST else it }
            }
    }
}
