package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.chapter.ChapterNumberHint
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.presentation.reader.SeededChapter

/** A novel's details page marks a chapter whose number is out of line, and its dialog opens on the fix. */
class NovelDetailsNumberHintTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a chapter out of line with its neighbours is marked with the number they leave free`() = runTest {
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novelId = harness.novel(harness.source("alpha"))
            val stray = seed(harness, novelId)
            harness.openDetails(novelId).awaitLoaded { it.chapters.size == 5 }.numberHints shouldBe
                mapOf(stray.id to ChapterNumberHint.Hint(1135.0))
        }
    }

    @Test
    fun `a merged series marks a stray from its own source's list`() = runTest {
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val leading = harness.novel(harness.source("alpha"))
            val other = harness.novel(harness.source("beta"))
            NUMBERS.filter { it != 1335.0 }.forEachIndexed { i, number ->
                harness.chapter(leading, number, sourceOrder = i.toLong())
            }
            // The second source lists only three; the merged list keeps the leading source's copies of
            // its neighbours, so the stray is out of line only in its own source's list.
            val stray = listOf(1134.0, 1335.0, 1136.0)
                .mapIndexed { i, number -> harness.chapter(other, number, sourceOrder = i.toLong()) }[1]
            harness.merge(leading, other)
            val loaded = harness.openDetails(leading).awaitLoaded { it.mergeSources.size == 2 && it.chapters.size == 5 }
            loaded.numberHints.keys shouldBe setOf(stray.id)
        }
    }

    @Test
    fun `the marker opens the number dialog on the suggestion`() = runTest {
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novelId = harness.novel(harness.source("alpha"))
            val stray = seed(harness, novelId)
            val model = harness.openDetails(novelId)
            model.awaitLoaded { it.chapters.size == 5 }
            model.showChapterNumberDialog(stray.id)
            val dialog = model.awaitLoaded { it.dialog is NovelDetailsDialog.ChapterNumber }.dialog
            (dialog as NovelDetailsDialog.ChapterNumber).edit.suggestion shouldBe 1135.0
        }
    }

    /** Real rows from a library, in the source's order; returns the stray, chapter 1135 listed as 1335. */
    private suspend fun seed(harness: NovelReaderViewModelHarness, novelId: Long): SeededChapter =
        NUMBERS.mapIndexed { i, number -> harness.chapter(novelId, number, sourceOrder = i.toLong()) }[2]

    private suspend fun NovelDetailsViewModel.awaitLoaded(
        predicate: (NovelDetailsState.Loaded) -> Boolean,
    ): NovelDetailsState.Loaded = withContext(Dispatchers.Default) {
        withTimeout(10_000) { state.filterIsInstance<NovelDetailsState.Loaded>().first(predicate) }
    }

    private companion object {
        val NUMBERS = listOf(1133.0, 1134.0, 1335.0, 1136.0, 1137.0)
    }
}
