package tachiyomi.data.source

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

class SourcePagingSourceTest {

    @Test
    fun `an extension built against a missing app class fails the page load instead of crashing`() = runTest {
        val source = mockk<Source> {
            coEvery { getPopularManga(any()) } throws NoClassDefFoundError("app/cash/quickjs/QuickJs")
        }
        val pagingSource = SourcePopularPagingSource({ source }, networkToLocalManga = mockk(relaxed = true))

        val result = pagingSource.load(refresh())

        result.shouldBeInstanceOf<PagingSource.LoadResult.Error<Long, *>>()
    }

    @Test
    fun `an empty first page reports no results`() = runTest {
        val result = pager(MangasPage(emptyList(), hasNextPage = false)).load(refresh())

        (result as PagingSource.LoadResult.Error).throwable.shouldBeInstanceOf<NoResultsException>()
    }

    @Test
    fun `an empty later page ends the catalogue quietly`() = runTest {
        val pager =
            pager(MangasPage(listOf(manga("a")), hasNextPage = true), MangasPage(emptyList(), hasNextPage = true))
        pager.load(refresh())

        (pager.load(append(2)) as PagingSource.LoadResult.Page).nextKey shouldBe null
    }

    @Test
    fun `a page repeating what has been seen still follows the source's next page`() = runTest {
        val pager = pager(MangasPage(listOf(manga("a")), hasNextPage = true), MangasPage(listOf(manga("a")), true))
        pager.load(refresh())

        (pager.load(append(2)) as PagingSource.LoadResult.Page).nextKey shouldBe 3L
    }

    private fun pager(vararg pages: MangasPage): SourcePopularPagingSource {
        val source = mockk<Source> {
            every { id } returns 1L
            coEvery { getPopularManga(any()) } answers { pages[firstArg<Int>() - 1] }
        }
        val networkToLocalManga = mockk<NetworkToLocalManga> {
            coEvery { this@mockk.invoke(any<List<Manga>>()) } answers { firstArg() }
        }
        return SourcePopularPagingSource({ source }, networkToLocalManga)
    }

    private fun manga(url: String) = SManga.create().apply {
        this.url = url
        title = url
    }

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
}
