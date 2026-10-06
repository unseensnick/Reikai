package reikai.novel.source

import androidx.paging.PagingSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.source.CatalogueEnd
import reikai.novel.host.NovelItem
import tachiyomi.data.source.NoResultsException

/** The novel pager follows the catalogue rule `CataloguePagingTest` pins, carrying each source's end. */
class NovelPagingSourceTest {

    @Test
    fun `an app source's reported next page is followed past a repeat`() = runTest {
        val source = FakePagingSource(listOf(page("a"), page("a")), end = CatalogueEnd.Reported(true))
        source.load(refresh())

        source.load(append(2)).nextKey() shouldBe 3L
    }

    @Test
    fun `a plugin's catalogue ends at a page repeating what has been seen`() = runTest {
        val source = FakePagingSource(listOf(page("a", "b"), page("a", "b")), end = CatalogueEnd.Inferred)
        source.load(refresh())

        source.load(append(2)).nextKey() shouldBe null
    }

    @Test
    fun `an empty first page reports no results`() = runTest {
        val source = FakePagingSource(listOf(emptyList()), end = CatalogueEnd.Reported(false))

        (source.load(refresh()) as PagingSource.LoadResult.Error).throwable.shouldBeInstanceOf<NoResultsException>()
    }

    @Test
    fun `an entry repeated across a page boundary is listed once`() = runTest {
        val source = FakePagingSource(listOf(page("a", "b"), page("b", "c")), end = CatalogueEnd.Inferred)
        source.load(refresh())

        source.load(append(2)).items().map { it.path } shouldBe listOf("c")
    }

    @Test
    fun `a failed fetch surfaces as an error rather than an end`() = runTest {
        val source = FakePagingSource(listOf(page("a")), end = CatalogueEnd.Inferred, failOnPage = 2)
        source.load(refresh())

        (source.load(append(2)) is PagingSource.LoadResult.Error) shouldBe true
    }

    private fun page(vararg paths: String) = paths.map { NovelItem(name = it, path = it) }

    private fun refresh() = PagingSource.LoadParams.Refresh<Long>(
        key = null,
        loadSize = 20,
        placeholdersEnabled = false,
    )

    private fun append(key: Long) = PagingSource.LoadParams.Append(
        key = key,
        loadSize = 20,
        placeholdersEnabled = false,
    )

    private fun PagingSource.LoadResult<Long, NovelItem>.nextKey() =
        (this as PagingSource.LoadResult.Page).nextKey

    private fun PagingSource.LoadResult<Long, NovelItem>.items() =
        (this as PagingSource.LoadResult.Page).data
}

private class FakePagingSource(
    private val pages: List<List<NovelItem>>,
    private val end: CatalogueEnd,
    private val failOnPage: Int? = null,
) : BaseNovelPagingSource(mockk(relaxed = true)) {

    override suspend fun requestNextPage(page: Int): NovelItemsPage {
        if (page == failOnPage) error("network")
        return NovelItemsPage(pages.getOrElse(page - 1) { emptyList() }, end)
    }
}
