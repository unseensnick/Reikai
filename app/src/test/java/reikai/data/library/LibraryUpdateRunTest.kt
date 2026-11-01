package reikai.data.library

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.merge.TestMergeManagers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

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

    @Test
    fun `an update checks several sources at once, up to five`() = runTest {
        var running = 0
        var most = 0

        updateBySource((1..8).toList(), sourceOf = { it }) {
            most = maxOf(most, ++running)
            delay(1)
            running--
        }

        most shouldBe 5
    }

    @Test
    fun `an update checks one entry of a source at a time, in order`() = runTest {
        val checked = mutableListOf<Int>()
        var running = 0
        var most = 0

        updateBySource(listOf(1, 2, 3), sourceOf = { "only" }) {
            most = maxOf(most, ++running)
            delay(1)
            checked += it
            running--
        }

        (most to checked) shouldBe (1 to listOf(1, 2, 3))
    }

    @Test
    fun `an update starts on the source with the most entries`() = runTest {
        val checked = mutableListOf<Int>()

        updateBySource(listOf(1, 2, 3), sourceOf = { if (it == 1) "small" else "big" }) { checked += it }

        checked.first() shouldBe 2
    }

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

    /** With merging off every source is its own card, so a stored group collapses nothing. */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `with series merging off a copy of a chapter the group had is announced`(type: ContentType) = runTest {
        val managers = TestMergeManagers(mapOf(type to mapOf(1L to 7L, 2L to 7L)), mergingOn = false)
        val run = LibraryUpdateRun(
            ReconcileMergedChapters(mockk(), stitchers = emptySet()),
            managers.manga,
            managers.novel,
            mockk<MergedChapterUnitRepository> { coEvery { getStitch(any(), any()) } returns stitch },
            LibraryPreferences(InMemoryPreferenceStore()),
        )

        run<Entry, Row>(
            type,
            entryId = { it.id },
            chapterId = { it.id },
            announce = { announced += it },
            queueDownloads = {},
        ) { it.arrived(second, listOf(Row(20)), emptyList()) }

        announced.flatten().flatMap { (_, rows) -> rows.map { it.id } } shouldBe listOf(20L)
    }
}
