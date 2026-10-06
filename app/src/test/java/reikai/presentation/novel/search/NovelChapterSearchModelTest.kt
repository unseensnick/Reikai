package reikai.presentation.novel.search

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.novel.download.NovelDownloadedTextsFixture
import reikai.novel.download.NovelDownloadedTextsFixture.chapter
import reikai.presentation.novel.search.NovelChapterSearchScreen.Model
import reikai.presentation.novel.search.NovelChapterSearchScreen.SearchOptions
import reikai.presentation.novel.search.NovelChapterSearchScreen.State

/** Chapters 10..12 on disk, listed by the source out of order; 11 cannot be read. */
class NovelChapterSearchModelTest {

    private val stored = mapOf(12L to "<p>two and two</p>", 10L to "<p>one, two</p>", 11L to null)
    private val chapters = listOf(chapter(12, order = 3), chapter(10, order = 1), chapter(11, order = 2))

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    // The model reads on the IO dispatcher, so each step suspends until the state it waits for arrives.
    private suspend fun searched(query: String, options: SearchOptions = SearchOptions()): State {
        val model = Model(1L, sourceScoped = false, NovelDownloadedTextsFixture.texts(chapters, stored))
        model.state.first { it.chapters != null }
        model.updateQuery(query)
        model.updateOptions(options)
        model.search()
        return model.state.first { (it.submittedQuery != null && !it.isSearching) || it.regexError != null }
    }

    @Test
    fun `each chapter with a match is listed in reading order with its count`() = runTest {
        searched("two").results.map { it.chapter.id to it.matchCount } shouldBe listOf(10L to 1, 12L to 2)
    }

    @Test
    fun `a chapter that cannot be read is counted apart`() = runTest {
        searched("two").failedCount shouldBe 1
    }

    @Test
    fun `whole words skips a match inside a word`() = runTest {
        searched("tw", SearchOptions(wholeWord = true)).results shouldBe emptyList()
    }

    @Test
    fun `an invalid pattern says why and searches nothing`() = runTest {
        searched("(two", SearchOptions(isRegex = true)).submittedQuery shouldBe null
    }
}
