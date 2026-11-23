package reikai.presentation.reader

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import java.io.IOException

/** What the manga reader stores for a page turn it held back, once the hold is written. */
class MangaProgressSaveTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `a page that failed to load does not displace the good page held before it`() = runTest {
        MangaReaderViewModelHarness.create().use { harness ->
            val manga = harness.manga(1L, source = 100L, title = "Series")
            harness.chapter(10L, manga, 1.0)
            harness.open(manga, chapterId = 10L) { model, state ->
                val pages = loadedPages(state.viewerChapters!!.currChapter, 10)
                harness.settle(model) {
                    model.onPageSelected(pages[5])
                    pages[6].status = Page.State.Error(IOException("no connection"))
                    model.onPageSelected(pages[6])
                    model.flushProgress()
                }
                harness.stored(10L).lastPageRead
            } shouldBe 5L
        }
    }
}
