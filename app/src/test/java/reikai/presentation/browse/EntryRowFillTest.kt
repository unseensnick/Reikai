package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import java.util.concurrent.atomic.AtomicInteger

/**
 * The three rules every fan-out over sources gets wrong the same way: one slow group starving the
 * others, a failure taking its neighbours with it, and work redone that was already in hand.
 */
class EntryRowFillTest {

    @Test
    fun `every waiting row is filled with what its load returned`() = runTest {
        val rows = MutableStateFlow(listOf(row("a"), row("b")))

        fill(rows) { listOf("${it.name}-result") }

        rows.value.map { it.entries() } shouldBe listOf(listOf("a-result"), listOf("b-result"))
    }

    @Test
    fun `a row that fails does not take its neighbours with it`() = runTest {
        val rows = MutableStateFlow(listOf(row("a"), row("bad"), row("c")))

        fill(rows) { if (it.name == "bad") error("no") else listOf(it.name) }

        rows.value.map { it.state is EntrySearchState.Success } shouldBe listOf(true, false, true)
    }

    @Test
    fun `a failure keeps what was thrown, for the screen to word`() = runTest {
        // Kept whole rather than as its message: an offline error arrives wrapped, and only the
        // shared formatter knows to read the cause underneath.
        val rows = MutableStateFlow(listOf(row("bad")))
        val thrown = IllegalStateException("source refused")

        fill(rows) { throw thrown }

        (rows.value.single().state as EntrySearchState.Error).error shouldBeSameInstanceAs thrown
    }

    @Test
    fun `a result that lands after its pass was cancelled is dropped`() = runTest {
        // Past its last cancellation point when the pass is cancelled, so only the check after the
        // load keeps it off whatever replaced the pass.
        val answers = CompletableDeferred<Unit>()
        val landed = mutableListOf<String>()
        val pass = launch {
            fanOutPerSource(
                items = listOf("a"),
                permits = { Semaphore(1) },
                load = { withContext(NonCancellable) { answers.await() } },
            ) { item, _ -> landed += item }
        }
        runCurrent()

        pass.cancel()
        answers.complete(Unit)
        advanceUntilIdle()

        landed shouldBe emptyList()
    }

    @Test
    fun `one item throwing costs only its own result`() = runTest {
        val landed = mutableMapOf<String, Boolean>()

        fanOutPerSource(
            items = listOf("a", "bad", "c"),
            permits = { Semaphore(SOURCE_SEARCH_CONCURRENCY) },
            load = { if (it == "bad") error("no") else it },
        ) { item, result -> landed[item] = result.isSuccess }

        landed shouldBe mapOf("a" to true, "bad" to false, "c" to true)
    }

    @Test
    fun `a row that already has results is not loaded again`() = runTest {
        val done = row("a").copy(state = EntrySearchState.Success(listOf(resultRow("kept"))))
        val rows = MutableStateFlow(listOf(done, row("b")))
        val loaded = mutableListOf<String>()

        fill(rows) {
            loaded += it.name
            listOf(it.name)
        }

        loaded shouldBe listOf("b")
    }

    @Test
    fun `rows are reordered as each one lands`() = runTest {
        val rows = MutableStateFlow(listOf(row("c"), row("a"), row("b")))

        fill(rows, order = compareBy { it.name }) { listOf(it.name) }

        rows.value.map { it.name } shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `two rows on one source are filled independently`() = runTest {
        // A feed holds a source twice, once for its latest and again for a saved search. Matching on
        // the source alone hands both rows whichever result lands last.
        val rows = MutableStateFlow(
            listOf(row("a").copy(id = "feed-1"), row("a").copy(id = "feed-2")),
        )

        fill(rows) { listOf(it.id) }

        rows.value.map { it.entries() } shouldBe listOf(listOf("feed-1"), listOf("feed-2"))
    }

    @Test
    fun `one group's slots do not hold up another group`() = runTest {
        // One permit each, so within a group loads are serial. Across groups they must overlap, which
        // is the whole point of a limiter per group rather than one shared across all of them.
        val rows = MutableStateFlow(
            listOf(row("m1"), row("m2"), row("n1", novel = true), row("n2", novel = true)),
        )
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()

        fill(rows, concurrency = 1) {
            peak.updateMax(inFlight.incrementAndGet())
            yield()
            inFlight.decrementAndGet()
            listOf(it.name)
        }

        peak.get() shouldBe 2
    }

    private suspend fun fill(
        rows: MutableStateFlow<List<BrowseSearchRow>>,
        order: Comparator<BrowseSearchRow>? = null,
        concurrency: Int = SOURCE_SEARCH_CONCURRENCY,
        load: suspend (BrowseSearchRow) -> List<String>,
    ) = fillEntryRows(
        rows = rows.value,
        group = { it.key.contentType },
        order = order,
        concurrency = concurrency,
        updateRows = { transform -> rows.update(transform) },
        load = { row -> load(row).map(::resultRow) },
    )
}

private fun row(name: String, novel: Boolean = false) = BrowseSearchRow(
    key = if (novel) SourceKey.Novel(name) else SourceKey.Manga(name.hashCode().toLong()),
    name = name,
    lang = "en",
    isPinned = false,
    state = EntrySearchState.Loading,
    source = name,
)

private fun BrowseSearchRow.entries() = (state as EntrySearchState.Success).entries.map { it.key }

private fun AtomicInteger.updateMax(candidate: Int) {
    while (true) {
        val current = get()
        if (candidate <= current || compareAndSet(current, candidate)) return
    }
}
