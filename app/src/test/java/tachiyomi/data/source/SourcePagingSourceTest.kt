package tachiyomi.data.source

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SourcePagingSourceTest {

    @Test
    fun `an extension built against a missing app class fails the page load instead of crashing`() = runTest {
        val source = mockk<Source> {
            coEvery { getPopularManga(any()) } throws NoClassDefFoundError("app/cash/quickjs/QuickJs")
        }
        val pagingSource = SourcePopularPagingSource({ source }, networkToLocalManga = mockk(relaxed = true))

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        )

        result.shouldBeInstanceOf<PagingSource.LoadResult.Error<Long, *>>()
    }
}
