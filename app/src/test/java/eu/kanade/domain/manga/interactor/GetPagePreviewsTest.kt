package eu.kanade.domain.manga.interactor

import android.content.Context
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewPage
import eu.kanade.tachiyomi.source.PagePreviewSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * A page list whose links expired is dropped by the image fetcher; a subscriber still showing it must get
 * the list fetched again, over a real disk cache and a source that hands out new links on every fetch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetPagePreviewsTest {

    // The cache holds its journal open and has no close, which Windows refuses to delete under.
    @TempDir(cleanup = CleanupMode.NEVER)
    lateinit var dir: File

    private val manga = Manga.create().copy(id = 1L, source = SOURCE_ID)

    private fun cache() = PagePreviewCache(mockk<Context> { every { cacheDir } returns dir }, Json)

    private fun source(vararg links: String) = mockk<PagePreviewSource> {
        every { id } returns SOURCE_ID
        coEvery { getPagePreviewList(any(), any(), 1) } returnsMany links.map { link ->
            PagePreviewPage(1, listOf(PagePreviewInfo(0, link)), hasNextPage = false, pagePreviewPages = 1)
        }
    }

    private fun getPagePreviews(cache: PagePreviewCache) = GetPagePreviews(
        cache,
        mockk<GetChaptersByMangaId> {
            coEvery { await(manga.id, any()) } returns listOf(Chapter.create().copy(id = 10L, mangaId = manga.id))
        },
    )

    private fun TestScope.collect(cache: PagePreviewCache, source: PagePreviewSource): List<GetPagePreviews.Result> {
        val results = mutableListOf<GetPagePreviews.Result>()
        backgroundScope.launch { getPagePreviews(cache).subscribe(manga, source, 1).toList(results) }
        runCurrent()
        return results
    }

    private fun List<GetPagePreviews.Result>.links() =
        map { result -> (result as GetPagePreviews.Result.Success).pagePreviews.map { it.imageUrl } }

    @Test
    fun `a dropped page list is fetched again with the new links`() = runTest {
        val cache = cache()
        val results = collect(cache, source("A", "B"))

        cache.removePageList(cache.pageListKey(manga, listOf(10L), 1))
        runCurrent()

        results.links() shouldBe listOf(listOf("A"), listOf("B"))
    }

    @Test
    fun `a list dropped again after its refetch is not fetched a third time`() = runTest {
        val cache = cache()
        val results = collect(cache, source("A", "B", "C"))
        val key = cache.pageListKey(manga, listOf(10L), 1)

        cache.removePageList(key)
        runCurrent()
        cache.removePageList(key)
        runCurrent()

        results.links() shouldBe listOf(listOf("A"), listOf("B"))
    }

    private companion object {
        const val SOURCE_ID = 7L
    }
}
