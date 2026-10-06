package reikai.domain.source

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The paging rule the manga and novel catalogue grids share. */
class CataloguePagingTest {

    @Test
    fun `a first load that comes back empty is no results`() {
        paging().take(1, isFirstLoad = true, items = emptyList(), end = CatalogueEnd.Reported(true)) shouldBe null
    }

    @Test
    fun `an empty later page ends the list even when the source reports another`() {
        val paging = paging().apply { take(1, true, listOf("a"), CatalogueEnd.Reported(true)) }

        paging.take(2, false, emptyList(), CatalogueEnd.Reported(true)) shouldBe CataloguePage(emptyList(), null)
    }

    @Test
    fun `a reported next page is followed`() {
        paging().take(1, true, listOf("a"), CatalogueEnd.Reported(true))?.nextKey shouldBe 2L
    }

    @Test
    fun `a reported last page ends the list`() {
        paging().take(1, true, listOf("a"), CatalogueEnd.Reported(false))?.nextKey shouldBe null
    }

    @Test
    fun `a reported next page is followed past a page of repeats`() {
        val paging = paging().apply { take(1, true, listOf("a"), CatalogueEnd.Reported(true)) }

        paging.take(2, false, listOf("a"), CatalogueEnd.Reported(true))?.nextKey shouldBe 3L
    }

    @Test
    fun `an inferred end follows a page that brought something new`() {
        val paging = paging().apply { take(1, true, listOf("a"), CatalogueEnd.Inferred) }

        paging.take(2, false, listOf("a", "b"), CatalogueEnd.Inferred)?.nextKey shouldBe 3L
    }

    @Test
    fun `an inferred end stops at a page of repeats`() {
        val paging = paging().apply { take(1, true, listOf("a"), CatalogueEnd.Inferred) }

        paging.take(2, false, listOf("a"), CatalogueEnd.Inferred)?.nextKey shouldBe null
    }

    @Test
    fun `an entry repeated across a page boundary is listed once`() {
        val paging = paging().apply { take(1, true, listOf("a", "b"), CatalogueEnd.Reported(true)) }

        paging.take(2, false, listOf("b", "c"), CatalogueEnd.Reported(true))?.fresh shouldBe listOf("c")
    }

    @Test
    fun `a refresh reloads from the key beside the page the reader was looking at`() {
        val page = PagingSource.LoadResult.Page(data = listOf("a"), prevKey = null, nextKey = 3L)
        val state =
            PagingState(listOf(page), anchorPosition = 0, config = PagingConfig(20), leadingPlaceholderCount = 0)

        state.catalogueRefreshKey() shouldBe 3L
    }

    private fun paging() = CataloguePaging<String> { it }
}
