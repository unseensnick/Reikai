package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
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
}
