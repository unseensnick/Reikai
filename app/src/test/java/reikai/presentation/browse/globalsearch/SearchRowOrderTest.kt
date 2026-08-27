package reikai.presentation.browse.globalsearch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey

/**
 * One global search orders and filters its rows once for both content types, so these pin what a
 * reader sees as the sources land: what rises, what sinks, and what the has-results toggle hides.
 */
class SearchRowOrderTest {

    @Test
    fun `sources that found something rise above ones that did not`() {
        // Named so name order alone would put the empty source first: only the hits clause can invert it.
        sorted(
            row("Alpha", hits = 0),
            row("Bravo", hits = 2),
        ) shouldBe listOf("Bravo", "Alpha")
    }

    @Test
    fun `a source still loading sinks below one that has results`() {
        sorted(
            loading("Alpha"),
            row("Bravo", hits = 1),
        ) shouldBe listOf("Bravo", "Alpha")
    }

    @Test
    fun `among sources that all found something, pinned ones lead`() {
        sorted(
            row("Alpha", hits = 1),
            row("Zed", hits = 1, isPinned = true),
        ) shouldBe listOf("Zed", "Alpha")
    }

    @Test
    fun `a manga source and a novel source order together`() {
        sorted(
            novel("Charlie", hits = 1),
            row("alpha", hits = 1),
            novel("Bravo", hits = 1),
        ) shouldBe listOf("alpha", "Bravo", "Charlie")
    }

    @Test
    fun `the has-results filter keeps only sources that found something`() {
        listOf(row("Alpha", hits = 2), row("Bravo", hits = 0), loading("Cy"), errored("Dee"))
            .filter { it.hasResults() }
            .map { it.name } shouldBe listOf("Alpha")
    }

    private fun sorted(vararg rows: BrowseSearchRow) =
        rows.sortedWith(searchRowComparator).map { it.name }

    private var nextId = 0L

    private fun row(name: String, hits: Int, isPinned: Boolean = false) = BrowseSearchRow(
        key = SourceKey.Manga(nextId++),
        name = name,
        lang = "en",
        isPinned = isPinned,
        state = EntrySearchState.Success(List(hits) { Any() }),
        source = Unit,
    )

    private fun novel(name: String, hits: Int) = row(name, hits).copy(key = SourceKey.Novel(name))

    private fun loading(name: String) = row(name, hits = 0).copy(state = EntrySearchState.Loading)

    private fun errored(name: String) =
        row(name, hits = 0).copy(state = EntrySearchState.Error("boom"))
}
