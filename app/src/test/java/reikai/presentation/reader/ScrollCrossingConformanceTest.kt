package reikai.presentation.reader

import io.kotest.matchers.shouldBe
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
 * Scrolling out of a chapter into the next one, past a read chapter the skip setting steps over, keeps
 * the chapter scrolled out of as the one behind, since it is still on screen. A step back would reach the
 * read one, which the reader never saw there. Pinned once over both readers.
 */
class ScrollCrossingConformanceTest {

    interface Probe {
        /** Opens chapter 1 of 1 (unread), 2 (read), 3 (unread), scrolls into 3, and answers the one behind. */
        suspend fun behindAfterScrollingInto3(scope: TestScope): Double?
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `scrolling past a skipped read chapter keeps the chapter scrolled out of behind`(probe: Probe) = runTest {
        probe.behindAfterScrollingInto3(this) shouldBe 1.0
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun behindAfterScrollingInto3(scope: TestScope): Double? =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                harness.chapter(20L, manga, 2.0, read = true)
                harness.chapter(30L, manga, 3.0)
                harness.open(manga, chapterId = 10L, preferences = mapOf("skip_read" to true)) { model, state ->
                    model.onPageSelected(loadedPages(state.viewerChapters!!.nextChapter!!, 5)[0])
                    settled(model) { it.viewerChapters?.currChapter?.chapter?.id == 30L }
                        .viewerChapters!!.prevChapter?.chapter?.chapterNumber
                }
            }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun behindAfterScrollingInto3(scope: TestScope): Double? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                harness.novelPreferences.readerSkipRead().set(true)
                // The chapter after joins the window at once, so there is one to scroll into.
                harness.novelPreferences.readerAutoLoadNextAt().set(0)
                val novel = harness.novel(harness.source("src"))
                val chapters = listOf(
                    harness.chapter(novel, 1.0),
                    harness.chapter(novel, 2.0, read = true),
                    harness.chapter(novel, 3.0),
                )
                val model = harness.open(novel, chapters[0].id)
                scope.advanceUntilIdle()
                model.rendererLanded(model.window.value.generation)

                model.reportVisibleChapter(chapters[2].id)
                scope.advanceUntilIdle()

                val behind = model.chapterNeighbours.value.previous
                listOf(1.0, 2.0, 3.0).getOrNull(chapters.indexOfFirst { it.id == behind })
            }
    }
}
