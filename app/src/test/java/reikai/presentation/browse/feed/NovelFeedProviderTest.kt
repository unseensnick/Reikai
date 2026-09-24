package reikai.presentation.browse.feed

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey
import reikai.domain.source.model.SavedSearch
import reikai.novel.host.NovelItem
import reikai.novel.source.NovelFilters
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState

/**
 * A feed row holding a saved search shows what that search's catalogue shows. An LNReader plugin's
 * filters-only search browsed Latest in the feed and Popular in the catalogue it opens, and several
 * plugins drop their filters on Latest, so the row showed the plain Latest list.
 */
class NovelFeedProviderTest {

    private val source = mockk<NovelSource>(relaxed = true) {
        every { id } returns "plugin"
        every { supportsLatest } returns true
        every { filters } returns NovelFilters.LnSchema(JsonObject(emptyMap()))
        coEvery { browse(any(), any(), any()) } answers {
            NovelItemsPage(listOf(NovelItem(firstArg<NovelListing>().name, "/x", null)), hasNextPage = false)
        }
    }

    private val row = BrowseSearchRow(
        key = SourceKey.Novel("plugin"),
        name = "Plugin",
        lang = "en",
        isPinned = false,
        state = EntrySearchState.Loading,
        source = source,
    )

    @Test
    fun `a filters-only plugin saved search pages the Popular listing its catalogue pages`() = runTest {
        val saved = SavedSearch(1L, SourceKey.Novel("plugin"), "Saved", query = null, filtersJson = null)

        val shown = NovelFeedProvider(mockk(), mockk()).load(row, saved)

        shown.map { (it as NovelItem).name } shouldBe listOf(NovelListing.Popular.name)
    }
}
