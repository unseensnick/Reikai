package reikai.novel.source

import androidx.paging.PagingSource
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.novel.host.NovelItem

/**
 * lnreader plugins report no `hasNextPage`, so where a catalogue ends is inferred here rather than
 * read off the response. These pin what that inference does.
 */
class NovelPagingSourceTest {

    @Test
    fun `a page that returned entries offers the next one`() = runTest {
        val source = FakePagingSource(listOf(page("a", "b")))

        source.load(refresh()).nextKey() shouldBe 2L
    }

    @Test
    fun `an empty page ends the catalogue`() = runTest {
        val source = FakePagingSource(listOf(emptyList()))

        source.load(refresh()).nextKey() shouldBe null
    }

    @Test
    fun `a page repeating what has already been seen ends the catalogue`() = runTest {
        // A plugin that answers an out-of-range page with a repeat of the last one would otherwise
        // page forever, since Paging keeps requesting while the key is non-null.
        val source = FakePagingSource(listOf(page("a", "b"), page("a", "b")))
        source.load(refresh())

        source.load(append(2)).nextKey() shouldBe null
    }

    @Test
    fun `an entry repeated across a page boundary is listed once`() = runTest {
        val source = FakePagingSource(listOf(page("a", "b"), page("b", "c")))
        source.load(refresh())

        source.load(append(2)).items().map { it.path } shouldBe listOf("c")
    }

    @Test
    fun `a failed fetch surfaces as an error rather than an end`() = runTest {
        val source = FakePagingSource(listOf(page("a")), failOnPage = 2)
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
    private val failOnPage: Int? = null,
) : BaseNovelPagingSource(mockk(relaxed = true)) {

    override suspend fun requestNextPage(page: Int): List<NovelItem> {
        if (page == failOnPage) error("network")
        return pages.getOrElse(page - 1) { emptyList() }
    }
}
