package reikai.presentation.reader

import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
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
 * The page a manga chapter reopens at when its viewer is built again, which the three image viewers
 * read straight off `requestedPage`. A finished chapter opens at its start when it is picked or stepped
 * to, but paging back into it must keep the page on screen, or a rotation drops the reader on page 1.
 * Manga only: a novel's position is typed and never passes through this field.
 */
class MangaRequestedPageTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `paging back into a finished chapter keeps the page it landed on`() = runTest {
        onFinishedChapter { model, finished ->
            val pages = loadedPages(finished, 10)
            model.onPageSelected(pages[5])
            becameCurrent(model, finished)
            finished.requestedPage
        } shouldBe 5
    }

    @Test
    fun `picking a finished chapter opens it at its start`() = runTest {
        onFinishedChapter { model, finished ->
            // The page it ended on, which every save of the session wrote here.
            finished.requestedPage = 9
            model.loadNewChapterFromDialog(finished.chapter.toDomainChapter()!!)
            becameCurrent(model, finished)
            finished.requestedPage
        } shouldBe 0
    }

    /** Chapter 1 read, opened on chapter 2 with Resume reading position off; [probe] gets chapter 1. */
    private suspend fun <T> onFinishedChapter(probe: suspend (ReaderViewModel, ReaderChapter) -> T): T =
        MangaReaderViewModelHarness.create().use { harness ->
            val manga = harness.manga(1L, source = 100L, title = "Series")
            harness.chapter(10L, manga, 1.0, read = true)
            harness.chapter(20L, manga, 2.0)
            harness.open(manga, chapterId = 20L) { model, state ->
                probe(model, state.viewerChapters!!.prevChapter!!)
            }
        }

    /** Waits for [chapter] to be the current one, then for the model's own collector of that change. */
    private suspend fun becameCurrent(model: ReaderViewModel, chapter: ReaderChapter) {
        settled(model) { it.viewerChapters?.currChapter?.chapter?.id == chapter.chapter.id }
        withContext(Dispatchers.Default) { delay(300) }
    }
}
