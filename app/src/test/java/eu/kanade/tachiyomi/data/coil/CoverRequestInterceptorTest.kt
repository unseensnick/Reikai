package eu.kanade.tachiyomi.data.coil

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelCover
import tachiyomi.domain.manga.model.MangaCover

/** Rows that show one cover at once share its load, for a manga's cover and a novel's alike. */
class CoverRequestInterceptorTest {

    private val interceptor = CoverRequestInterceptor()
    private val loadFinishes = CompletableDeferred<Unit>()
    private var loading = 0
    private var mostLoadingAtOnce = 0

    @Test
    fun `two rows showing one manga cover load it once at a time`() = runTest {
        twoRowsAtOnce(MangaCover(mangaId = 1L, sourceId = 1L, isMangaFavorite = true, url = "u", lastModified = 0L))

        mostLoadingAtOnce shouldBe 1
    }

    @Test
    fun `two rows showing one novel cover load it once at a time`() = runTest {
        twoRowsAtOnce(NovelCover(url = "u", sourceId = "s", isNovelFavorite = true, lastModified = 0L, novelId = 1L))

        mostLoadingAtOnce shouldBe 1
    }

    private suspend fun TestScope.twoRowsAtOnce(data: Any) {
        val chain = mockk<Interceptor.Chain> {
            every { request.data } returns data
            every { size } returns Size.ORIGINAL
            coEvery { proceed() } coAnswers {
                mostLoadingAtOnce = maxOf(mostLoadingAtOnce, ++loading)
                loadFinishes.await()
                loading--
                mockk<SuccessResult>() as ImageResult
            }
        }
        val rows = List(2) { launch { interceptor.intercept(chain) } }
        testScheduler.runCurrent()
        loadFinishes.complete(Unit)
        rows.forEach { it.join() }
    }
}
