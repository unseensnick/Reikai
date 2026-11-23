package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.reader.ChapterProgress
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers

/**
 * A position the reader is still holding back is written when the host pauses, since a backgrounded reader
 * can be killed before the hold runs out. Pinned once over both readers, with the hold's own delay never
 * reached, so only the flush can have written it.
 */
class ReaderPauseFlushConformanceTest {

    interface Probe {
        /** Reports a position that is held, flushes, and answers whether that position is stored. */
        suspend fun heldPositionStoredOnFlush(scope: TestScope): Boolean
    }

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `pausing writes the position the reader is holding back`(probe: Probe) = runTest {
        probe.heldPositionStoredOnFlush(this) shouldBe true
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        // Waited on without suspending first, which would let virtual time run out the hold.
        override suspend fun heldPositionStoredOnFlush(scope: TestScope): Boolean =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                harness.open(manga, chapterId = 10L) { model, state ->
                    model.onPageSelected(loadedPages(state.viewerChapters!!.currChapter, 5)[3])
                    val before = harness.appWrites()

                    model.flushProgress()
                    (harness.appWrites() - before).joinAll()

                    harness.stored(10L).lastPageRead == 3
                }
            }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun heldPositionStoredOnFlush(scope: TestScope): Boolean =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                val chapter = harness.chapter(novel, 1.0)
                val model = harness.open(novel, chapter.id)
                scope.advanceUntilIdle()
                model.rendererLanded(model.window.value.generation)
                // The first report is where the landing put the reader; a second that moved is theirs.
                model.reportProgress(chapter.id, ChapterProgress.Percent.ofWhole(40))
                model.reportProgress(chapter.id, ChapterProgress.Percent.ofWhole(45))
                scope.runCurrent()

                model.flushProgress()
                scope.runCurrent()

                harness.progressOf(chapter) == ChapterProgress.Percent.ofWhole(45).hundredths
            }
    }
}
