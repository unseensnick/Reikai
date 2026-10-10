package reikai.presentation.reader

import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/**
 * Finishing a chapter marks its other copies read as the database holds them when it finishes, not as
 * the session saw them at open. Pinned once over both readers.
 *
 * One entry lists chapters 1, 2 and 3 by group "x"; the reader opens on 1, steps to 2, the [Change]
 * happens to group "y"'s copy of chapter 2, then a Next finishes 2.
 */
class MarkDuplicateReadConformanceTest {

    enum class Change {
        /** "y"'s copy, read at open, is marked unread mid-session. */
        UNMARKED,

        /** "y"'s copy arrives mid-session, as a refresh stores it. */
        ADDED,
    }

    interface Probe {
        /** Whether "y"'s copy of chapter 2 is stored read once 2 is finished after [change]. */
        suspend fun copyReadAfterFinish(scope: TestScope, change: Change): Boolean?
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a copy marked unread mid-session is marked read when its twin is finished`(probe: Probe) = runTest {
        probe.copyReadAfterFinish(this, Change.UNMARKED) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a copy added mid-session is marked read when its twin is finished`(probe: Probe) = runTest {
        probe.copyReadAfterFinish(this, Change.ADDED) shouldBe true
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun copyReadAfterFinish(scope: TestScope, change: Change): Boolean? =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0, scanlator = "x")
                harness.chapter(20L, manga, 2.0, scanlator = "x")
                if (change == Change.UNMARKED) harness.chapter(21L, manga, 2.0, scanlator = "y", read = true)
                harness.chapter(30L, manga, 3.0, scanlator = "x")
                val prefs = mapOf("mark_read_on_skip" to true, "skip_dupe" to true)
                harness.open(manga, chapterId = 10L, preferences = prefs) { model, _ ->
                    model.nextAndSettle()
                    when (change) {
                        Change.UNMARKED -> harness.setRead(21L, read = false)
                        Change.ADDED -> harness.chapter(21L, manga, 2.0, scanlator = "y")
                    }
                    model.nextAndSettle()
                    harness.stored(21L).read
                }
            }

        /** The mark is written on the IO dispatcher, which virtual time does not reach, so it is waited out. */
        private suspend fun ReaderViewModel.nextAndSettle() {
            val scopeJob = viewModelScope.coroutineContext.job
            val running = scopeJob.children.toSet()
            loadNextChapter()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { (scopeJob.children.toSet() - running).joinAll() }
            }
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun copyReadAfterFinish(scope: TestScope, change: Change): Boolean? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                harness.novelPreferences.readerMarkReadOnSkip().set(true)
                harness.novelPreferences.readerSkipDuplicateChapters().set(true)
                val novel = harness.novel(harness.source("src"))
                val one = harness.chapter(novel, 1.0, scanlator = "x")
                harness.chapter(novel, 2.0, scanlator = "x")
                suspend fun copy(read: Boolean) =
                    harness.chapter(novel, 2.0, read, url = "/2-y", sourceOrder = 3L, scanlator = "y")
                val seeded = if (change == Change.UNMARKED) copy(true) else null
                harness.chapter(novel, 3.0, sourceOrder = 4L, scanlator = "x")
                val model = harness.open(novel, one.id)
                scope.advanceUntilIdle()

                model.nextChapter()
                scope.advanceUntilIdle()
                val other = when (change) {
                    Change.UNMARKED -> seeded!!.also { harness.markRead(it, read = false) }
                    Change.ADDED -> copy(false)
                }
                model.nextChapter()
                scope.advanceUntilIdle()

                harness.isRead(other)
            }
    }
}
