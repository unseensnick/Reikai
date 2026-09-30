package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.chapter.hiddenChapterKey
import reikai.domain.novel.model.NovelChapterFlags
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.presentation.reader.SeededChapter

/**
 * Mark previous as read walks the chapters the list shows, as manga's does, across every page of a
 * paged novel: the list shows one page, so the earlier pages are its stored rows under the same filters.
 */
class NovelMarkPreviousReadTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `on a paged novel it marks only the earlier chapters the filters show`() = runTest {
        earlierReadAfterMarkingPrevious { harness, novelId ->
            harness.novelPreferences.defaultChapterFilterBookmarked().set(NovelChapterFlags.SHOW_BOOKMARKED)
            listOf(
                harness.chapter(novelId, 1.0, bookmark = true, page = "1"),
                harness.chapter(novelId, 2.0, page = "1"),
            )
        } shouldBe listOf(true, false)
    }

    @Test
    fun `on a paged novel it leaves a hidden chapter unread`() = runTest {
        earlierReadAfterMarkingPrevious { harness, novelId ->
            val hidden = harness.chapter(novelId, 2.0, page = "1")
            harness.novelPreferences.hiddenChapters().set(setOf(hiddenChapterKey(SOURCE, hidden.url)))
            listOf(harness.chapter(novelId, 1.0, page = "1"), hidden)
        } shouldBe listOf(true, false)
    }

    /**
     * Seeds the chapters [seed] returns on page 1 and a bookmarked pointer on page 2, marks previous as
     * read from the pointer, and answers whether each seeded chapter came out read.
     */
    private suspend fun TestScope.earlierReadAfterMarkingPrevious(
        seed: suspend (NovelReaderViewModelHarness, Long) -> List<SeededChapter>,
    ): List<Boolean> = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
        val novelId = harness.novel(harness.source(SOURCE))
        val earlier = seed(harness, novelId)
        val pointer = harness.chapter(novelId, 3.0, bookmark = true, page = "2")
        val model = harness.openDetails(novelId)
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                model.state.first { it is NovelDetailsState.Loaded && it.pages.size == 2 }
                model.selectPage(1)
                model.state.first {
                    (it as? NovelDetailsState.Loaded)?.chapters?.map { c -> c.id } == listOf(pointer.id)
                }
                model.toggleSelection(pointer.id, fromLongPress = false)
                model.markPreviousRead()
            }
            withTimeoutOrNull(5_000) { while (earlier.none { harness.isRead(it) == true }) delay(20) }
            delay(200)
        }
        earlier.map { harness.isRead(it) == true }
    }

    private companion object {
        const val SOURCE = "alpha"
    }
}
