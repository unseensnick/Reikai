package reikai.domain.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Both readers hold their position back through this kernel, so its timing is the one rule pinning them alike. */
class ReaderProgressSaveTest {

    @Test
    fun `a burst of page turns writes once, the last page, after the delay`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        (1..5).forEach { save.report(1L, it) }
        advanceTimeBy(PROGRESS_SAVE_DELAY.inWholeMilliseconds + 1)

        written shouldBe listOf(1L to 5)
    }

    @Test
    fun `each report restarts the delay, so a steady scroll writes nothing until it settles`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 1)
        advanceTimeBy(PROGRESS_SAVE_DELAY.inWholeMilliseconds - 100)
        save.report(1L, 2)
        advanceTimeBy(200)

        written shouldBe emptyList()
    }

    @Test
    fun `nothing is written before the delay runs out`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 3)
        advanceTimeBy(PROGRESS_SAVE_DELAY.inWholeMilliseconds - 1)

        written shouldBe emptyList()
    }

    @Test
    fun `a position that finishes the chapter is written at once`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 3)
        save.report(1L, 9, now = true)
        runCurrent()

        written shouldBe listOf(1L to 9)
    }

    @Test
    fun `a flush on pause writes the held position without waiting`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 4)
        save.flush()

        written shouldBe listOf(1L to 4)
    }

    /** Read aloud reports after the host's flush on pause, and the reader can close inside the delay. */
    @Test
    fun `the reader closing writes the held position`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val readerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val save = ReaderProgressSave<Long, Int>(readerScope) { id, page -> written += id to page }

        save.report(1L, 4)
        readerScope.cancel()
        runCurrent()

        written shouldBe listOf(1L to 4)
    }

    @Test
    fun `a page in another chapter writes the held one first`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 7)
        save.report(2L, 0)

        written shouldBe listOf(1L to 7)
    }

    @Test
    fun `a discarded position is never written`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 7)
        save.discardIf { it == 1L }
        advanceTimeBy(PROGRESS_SAVE_DELAY.inWholeMilliseconds + 1)

        written shouldBe emptyList()
    }

    @Test
    fun `a discard for another chapter keeps the held position`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 7)
        save.discardIf { it == 2L }
        advanceTimeBy(PROGRESS_SAVE_DELAY.inWholeMilliseconds + 1)

        written shouldBe listOf(1L to 7)
    }

    @Test
    fun `the held position answers only for its own chapter`() = runTest {
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { _, _ -> }

        save.report(1L, 7)

        save.heldFor(2L) shouldBe null
    }

    @Test
    fun `the held position is what its chapter reads before it is written`() = runTest {
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { _, _ -> }

        save.report(1L, 7)

        save.heldFor(1L) shouldBe 7
    }
}
