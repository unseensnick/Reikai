package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Test

class WebGpuPageCacheTest {

    private val cached = ReaderPage(3, "/1/3", "https://img/3")

    @Test
    fun `the page the wrapper was built for is still current`() {
        isCachedPageCurrent(cached, requested = cached) shouldBe true
    }

    /** A reload keeps the chapter and the index but loads new pages, whose images replace the old. */
    @Test
    fun `a new page at the same chapter and index replaces the cached one`() {
        isCachedPageCurrent(cached, requested = ReaderPage(3, "/1/3", "https://img/3")) shouldBe false
    }

    @Test
    fun `a chapter that loaded again with fewer pages has nothing left at that index`() {
        isCachedPageCurrent(cached, requested = null) shouldBe false
    }

    @Test
    fun `a replaced page's download is cancelled`() {
        val download = Job()

        cancelPageJobs("key", loadJobs = mutableMapOf("key" to download), watchJobs = mutableMapOf())

        download.isCancelled shouldBe true
    }

    @Test
    fun `a replaced page's status watcher is cancelled`() {
        val watcher = Job()

        cancelPageJobs("key", loadJobs = mutableMapOf(), watchJobs = mutableMapOf("key" to watcher))

        watcher.isCancelled shouldBe true
    }

    /** The page taking over the key registers its own jobs; a stale one left there would be cancelled in its place. */
    @Test
    fun `a replaced page's jobs leave the key free for the page replacing it`() {
        val loadJobs = mutableMapOf<String, Job>("key" to Job())

        cancelPageJobs("key", loadJobs, watchJobs = mutableMapOf())

        loadJobs shouldBe emptyMap()
    }
}
