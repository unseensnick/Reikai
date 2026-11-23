package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A step or a chapter-sheet pick stamps the chapter it leaves into history, with the time read in it,
 * before the next one starts its own session. Pinned once over both readers, on a wall clock the test
 * moves by [READ].
 */
class ReaderStepHistoryConformanceTest {

    interface Probe {
        /** Opens chapter 1 of two, reads it for [READ], moves to chapter 2 by a Next step or by a [pick], and
         *  answers the time History holds for chapter 1, null when it holds no row. */
        suspend fun timeReadInLeftChapter(scope: TestScope, pick: Boolean): Long?
    }

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
        now = Instant.fromEpochMilliseconds(1_000_000_000_000L)
        mockkObject(Clock.System)
        every { Clock.System.now() } answers { now }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(Clock.System)
        resetVirtualDispatchers()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a Next step puts the chapter it leaves in history with the time read in it`(probe: Probe) = runTest {
        probe.timeReadInLeftChapter(this, pick = false) shouldBe READ.inWholeMilliseconds
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a pick from the chapter sheet puts the chapter it leaves in history with the time read in it`(
        probe: Probe,
    ) = runTest {
        probe.timeReadInLeftChapter(this, pick = true) shouldBe READ.inWholeMilliseconds
    }

    companion object {
        val READ = 10.minutes

        @Volatile
        private var now = Instant.fromEpochMilliseconds(0L)

        /** The reader reading for [READ]. */
        fun read() {
            now += READ
        }

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun timeReadInLeftChapter(scope: TestScope, pick: Boolean): Long? =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                val second = harness.chapter(20L, manga, 2.0)
                harness.open(manga, chapterId = 10L) { model, _ ->
                    model.restartReadTimer()
                    read()
                    if (pick) {
                        model.loadNewChapterFromDialog(second)
                        settled(model) { it.viewerChapters?.currChapter?.chapter?.id == 20L }
                    } else {
                        model.loadNextChapter()
                    }
                    harness.readTimeOf(manga.id, 10L)
                }
            }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun timeReadInLeftChapter(scope: TestScope, pick: Boolean): Long? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val source = harness.source("src")
                val novel = harness.novel(source)
                val first = harness.chapter(novel, 1.0)
                val second = harness.chapter(novel, 2.0)
                val model = harness.open(novel, first.id)
                scope.advanceUntilIdle()
                read()

                if (pick) model.open(second.id) else model.nextChapter()
                scope.advanceUntilIdle()

                harness.readTimeOf(novel, first)
            }
    }
}
