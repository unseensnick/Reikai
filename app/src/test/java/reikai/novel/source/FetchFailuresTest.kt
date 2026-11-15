package reikai.novel.source

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.novel.host.SourceNovel

/** Which results a failed request inside the call stands in for, and which it leaves alone. */
class FetchFailuresTest {

    // A paged plugin leaves the chapters to parsePage, so a novel with pages and none listed yet was found.
    @Test
    fun `a paged novel with its chapters left to its pages is kept beside a failed request`() = runTest {
        val novel = SourceNovel(path = "n", name = "N", totalPages = 3)

        novelOrFailedFetch {
            currentCoroutineContext()[FetchFailures]!!.record(HttpException(404))
            novel
        } shouldBe novel
    }
}
