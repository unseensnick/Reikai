package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/**
 * A mark from the reader's chapter sheet lands in the database whatever the session saw of the chapter
 * when it opened. The manga reader built its targets from the chapters it loaded at open, so once a
 * chapter was marked read there, marking it unread wrote nothing. Pinned once over both readers.
 */
class ReaderSheetReadConformanceTest {

    /** One reader opened on chapter 1, with chapter 2 unread. */
    interface Probe {
        /** Marks chapter 2 read from the sheet, then unread when [unmark]: whether it is stored read afterwards. */
        suspend fun readAfterMarking(scope: TestScope, unmark: Boolean): Boolean?

        /** Marks chapters 2 and 3 read in one write, as the sheet's bulk bar does: whether each is stored read. */
        suspend fun readAfterMarkingTwo(scope: TestScope): List<Boolean?>
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `marking a chapter read from the sheet stores it read`(probe: Probe) = runTest {
        probe.readAfterMarking(this, unmark = false) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `marking a chapter read and then unread from the sheet leaves it unread`(probe: Probe) = runTest {
        probe.readAfterMarking(this, unmark = true) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `marking two chapters read at once from the sheet stores both read`(probe: Probe) = runTest {
        probe.readAfterMarkingTwo(this) shouldBe listOf(true, true)
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        /** The sheet writes on the IO dispatcher, which virtual time does not reach, so this polls. */
        private suspend fun settlesAt(expected: Boolean, read: suspend () -> Boolean?): Boolean? =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(3_000) { while (read() != expected) delay(20) }
                read()
            }
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun readAfterMarking(scope: TestScope, unmark: Boolean): Boolean? =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                val other = harness.chapter(20L, manga, 2.0)
                harness.open(manga, chapterId = 10L) { model, _ ->
                    model.setChapterReadStatus(listOf(other), read = true)
                    val marked = settlesAt(true) { harness.stored(other.id).read }
                    if (!unmark) return@open marked
                    model.setChapterReadStatus(listOf(other), read = false)
                    settlesAt(false) { harness.stored(other.id).read }
                }
            }

        override suspend fun readAfterMarkingTwo(scope: TestScope): List<Boolean?> =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                val marked = listOf(harness.chapter(20L, manga, 2.0), harness.chapter(30L, manga, 3.0))
                harness.open(manga, chapterId = 10L) { model, _ ->
                    model.setChapterReadStatus(marked, read = true)
                    marked.map { chapter -> settlesAt(true) { harness.stored(chapter.id).read } }
                }
            }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun readAfterMarking(scope: TestScope, unmark: Boolean): Boolean? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                val opened = harness.chapter(novel, 1.0)
                val other = harness.chapter(novel, 2.0)
                val model = harness.open(novel, opened.id)
                scope.advanceUntilIdle()

                model.setChapterRead(listOf(other.id), read = true)
                val marked = settlesAt(true) { harness.isRead(other) }
                if (!unmark) return@use marked
                model.setChapterRead(listOf(other.id), read = false)
                settlesAt(false) { harness.isRead(other) }
            }

        override suspend fun readAfterMarkingTwo(scope: TestScope): List<Boolean?> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                val opened = harness.chapter(novel, 1.0)
                val marked = listOf(harness.chapter(novel, 2.0), harness.chapter(novel, 3.0))
                val model = harness.open(novel, opened.id)
                scope.advanceUntilIdle()

                model.setChapterRead(marked.map { it.id }, read = true)
                marked.map { chapter -> settlesAt(true) { harness.isRead(chapter) } }
            }
    }
}
