package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The page a process-death restore and a reload put the reader back on is saved beside the chapter it
 * belongs to. Scrolling onto the next chapter's first page whose switch then fails leaves the reader in
 * the chapter it was in, so the saved page must still be that chapter's.
 */
class MangaRestorePageTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a page of a neighbour whose switch failed does not replace the saved page`() = runTest {
        MangaReaderViewModelHarness.create().use { harness ->
            val manga = harness.manga(1L, source = 100L, title = "Series")
            harness.chapter(10L, manga, 1.0)
            harness.chapter(20L, manga, 2.0)
            harness.open(manga, chapterId = 10L, failing = setOf(20L)) { model, state ->
                val chapters = state.viewerChapters!!
                model.onPageSelected(loadedPages(chapters.currChapter, 10)[5])
                model.onPageSelected(loadedPages(chapters.nextChapter!!, 10)[0])
                // The switch loads on the IO dispatcher, off the virtual clock, and fails there.
                withContext(Dispatchers.Default) { delay(300) }
                harness.savedState?.get<Int>("page_index")
            } shouldBe 5
        }
    }
}
