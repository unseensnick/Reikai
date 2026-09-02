package reikai.presentation.browse.feed

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey
import reikai.novel.host.NovelItem
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import tachiyomi.domain.manga.model.Manga

/**
 * What select-all and invert act on. The rows carry their entries as `Any`, so the split back into the
 * two halves is the one place a wrong unwrap would compile and then hand a bulk model the other type's
 * rows, which it would file into the wrong library.
 */
class FeedSelectionTest {

    @Test
    fun `each row's entries go to the half that owns them`() {
        val state = feedState(
            row(SourceKey.Manga(1L), EntrySearchState.Success(listOf(manga(10L), manga(11L)))),
            row(SourceKey.Novel("nb"), EntrySearchState.Success(listOf(novel("/a"), novel("/b")))),
        )

        val (manga, novels) = state.listedEntries()

        manga.map { it.id } shouldBe listOf(10L, 11L)
        novels.map { it.sourceId to it.item.path } shouldBe listOf("nb" to "/a", "nb" to "/b")
    }

    @Test
    fun `a novel is tagged with the source whose row it came from`() {
        // Two plugins can return the same path, so a novel that lost its source id would be filed
        // against whichever row happened to be first.
        val state = feedState(
            row(SourceKey.Novel("first"), EntrySearchState.Success(listOf(novel("/same")))),
            row(SourceKey.Novel("second"), EntrySearchState.Success(listOf(novel("/same")))),
        )

        val (_, novels) = state.listedEntries()

        novels.map { it.sourceId } shouldBe listOf("first", "second")
    }

    @Test
    fun `a row that has not answered contributes nothing`() {
        val state = feedState(
            row(SourceKey.Manga(1L), EntrySearchState.Loading),
            row(SourceKey.Manga(2L), EntrySearchState.Error("nope")),
            row(SourceKey.Novel("gone"), EntrySearchState.Unavailable),
        )

        val (manga, novels) = state.listedEntries()

        manga.shouldBeEmpty()
        novels.shouldBeEmpty()
    }

    private fun feedState(vararg entries: FeedEntry) = FeedState(entries = entries.toList(), loaded = true)

    private fun row(key: SourceKey, state: EntrySearchState) = FeedEntry(
        feedId = key.serialize().hashCode().toLong(),
        savedSearch = null,
        row = BrowseSearchRow(
            key = key,
            name = key.serialize(),
            lang = "en",
            isPinned = false,
            state = state,
            source = Unit,
        ),
        sourceName = key.serialize(),
        supportsLatest = false,
    )

    private fun manga(id: Long) = Manga.create().copy(id = id, url = "/$id", title = "m$id")

    private fun novel(path: String) = NovelItem(name = "n$path", path = path, cover = null)
}
