package reikai.data.library

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterUnit

/**
 * One update run's bookkeeping, for both content types: the jobs differ only in what fills the ledger
 * and in how they announce, count and queue, all of which are recorded here.
 */
class LibraryUpdateRunTest {

    private data class Entry(val id: Long)

    private data class Row(val id: Long)

    private val first = Entry(1)
    private val second = Entry(2)

    /** Group 7 over entries 1 and 2: chapters 10 and 20 are one merged chapter, 11 is another. */
    private val stitch = listOf(
        ChapterUnit(chapterId = 10, unit = 0, copyOrder = 0),
        ChapterUnit(chapterId = 20, unit = 0, copyOrder = 1),
        ChapterUnit(chapterId = 11, unit = 1, copyOrder = 0),
    )

    private val events = mutableListOf<String>()
    private val announced = mutableListOf<List<Pair<Entry, List<Row>>>>()
    private var badge = 0
    private val queued = mutableListOf<Long>()

    private suspend fun update(
        announce: suspend (List<Pair<Entry, List<Row>>>) -> Unit = { announced += it },
        run: suspend (UpdateRunLedger<Entry, Row>) -> Unit,
    ) = runLibraryUpdate(
        afterPass = { pass ->
            try {
                pass()
            } finally {
                events += "reconciled"
            }
        },
        entryId = { it.id },
        chapterId = { it.id },
        memberships = { mapOf(1L to 7L, 2L to 7L) },
        stitchOf = {
            events += "stitch read"
            stitch
        },
        announce = announce,
        countArrivals = { badge += it },
        queueDownloads = { downloads ->
            // Suspends, as the real queue does, so only a non-cancellable finish reaches the end.
            yield()
            downloads.flatMapTo(queued) { (_, chapters) -> chapters.map { it.id } }
        },
        run = run,
    )

    private suspend fun UpdateRunLedger<Entry, Row>.bothSourcesGainChapterTen(eligible: Boolean = true) {
        arrived(first, listOf(Row(10)), if (eligible) listOf(Row(10)) else emptyList())
        arrived(second, listOf(Row(20)), listOf(Row(20)))
    }

    /** Starts a run that stops at [run]'s or [announce]'s suspension, then cancels it there. */
    private fun TestScope.cancelMidRun(
        announce: suspend (List<Pair<Entry, List<Row>>>) -> Unit = { announced += it },
        run: suspend (UpdateRunLedger<Entry, Row>) -> Unit = {
            it.bothSourcesGainChapterTen()
            awaitCancellation()
        },
    ) {
        val job = launch { update(announce, run) }
        runCurrent()
        job.cancel()
        runCurrent()
    }

    @Test
    fun `a finished run announces one copy per merged chapter`() = runTest {
        update { it.bothSourcesGainChapterTen() }

        announced.flatten().flatMap { (_, rows) -> rows.map { it.id } } shouldBe listOf(10L)
    }

    @Test
    fun `a finished run queues one download per merged chapter`() = runTest {
        update { it.bothSourcesGainChapterTen() }

        queued.size shouldBe 1
    }

    @Test
    fun `the copy downloaded is one the run found eligible, not the one announced`() = runTest {
        update { it.bothSourcesGainChapterTen(eligible = false) }

        queued shouldBe listOf(20L)
    }

    @Test
    fun `a finished run counts what it announced`() = runTest {
        update { it.bothSourcesGainChapterTen() }

        badge shouldBe 1
    }

    @Test
    fun `the stitch is read after the run is reconciled`() = runTest {
        update { it.bothSourcesGainChapterTen() }

        events shouldBe listOf("reconciled", "stitch read")
    }

    @Test
    fun `a cancelled run still counts everything it fetched`() = runTest {
        cancelMidRun()

        badge shouldBe 2
    }

    @Test
    fun `a cancelled run still queues everything it fetched`() = runTest {
        cancelMidRun()

        queued shouldBe listOf(10L, 20L)
    }

    @Test
    fun `a run cancelled while announcing counts one per merged chapter`() = runTest {
        cancelMidRun(announce = { awaitCancellation() }, run = { it.bothSourcesGainChapterTen() })

        badge shouldBe 1
    }

    @Test
    fun `a run with nothing to check announces nothing`() = runTest {
        update { }

        announced shouldBe emptyList()
    }

    @Test
    fun `arrivals that were all copies of chapters the group had announce nothing`() = runTest {
        update { it.arrived(second, listOf(Row(20)), listOf(Row(20))) }

        announced shouldBe emptyList()
    }
}
