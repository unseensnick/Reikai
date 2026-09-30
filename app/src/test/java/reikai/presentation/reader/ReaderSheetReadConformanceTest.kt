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
        /** Marks chapter 2 read and then unread from the sheet: whether it is stored read afterwards. */
        suspend fun readAfterMarkingReadThenUnread(scope: TestScope): Boolean?
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `marking a chapter read and then unread from the sheet leaves it unread`(probe: Probe) = runTest {
        probe.readAfterMarkingReadThenUnread(this) shouldBe false
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

        override suspend fun readAfterMarkingReadThenUnread(scope: TestScope): Boolean? =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                val other = harness.chapter(20L, manga, 2.0)
                harness.open(manga, chapterId = 10L) { model, _ ->
                    model.setChapterReadStatus(other, read = true)
                    settlesAt(true) { harness.stored(other.id).read }
                    model.setChapterReadStatus(other, read = false)
                    settlesAt(false) { harness.stored(other.id).read }
                }
            }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun readAfterMarkingReadThenUnread(scope: TestScope): Boolean? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                val opened = harness.chapter(novel, 1.0)
                val other = harness.chapter(novel, 2.0)
                val model = harness.open(novel, opened.id)
                scope.advanceUntilIdle()

                model.setChapterRead(other.id, read = true)
                settlesAt(true) { harness.isRead(other) }
                model.setChapterRead(other.id, read = false)
                settlesAt(false) { harness.isRead(other) }
            }
    }
}
