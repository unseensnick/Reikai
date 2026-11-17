package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import io.kotest.matchers.collections.shouldContain
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
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The manga finish asks whether a chapter is read of the row as stored, as the novel finish does:
 * mark duplicate read writes a copy read there and not in the chapters the reader loaded at open.
 * One entry lists chapter 5 by group "x" (50) and by group "y" (51), then chapter 6 (60).
 */
class MangaStoredReadStateTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `reading a copy marked read with its twin keeps it read`() = runTest {
        onFinishedFive(slots = -1) { harness, model ->
            model.loadNextChapter()
            val copy = model.state.value.viewerChapters!!.currChapter
            model.onPageSelected(loadedPages(copy, 10)[3])
            model.flushProgress()
            writesLand()
            harness.stored(51L).read
        } shouldBe true
    }

    /** Every resume gate reads the chapter the reader holds, so a pick of the copy opens it at its start. */
    @Test
    fun `the copy marked read with its twin is read in the chapters the reader holds`() = runTest {
        onFinishedFive(slots = -1) { _, model ->
            model.getChapters().first { it.chapter.id == 51L }.chapter.read
        } shouldBe true
    }

    @Test
    fun `finishing the next chapter retires the copy marked read with its twin`() = runTest {
        val queued = mutableSetOf<Long>()
        onFinishedFive(slots = 1, onEnqueueDelete = { chapters -> chapters.forEach { queued += it.id } }) { _, model ->
            // Straight from 5 to 6, as with Skip duplicates off the copy is stepped over by a pick.
            model.loadNewChapterFromDialog(model.getChapters().first { it.chapter.id == 60L }.chapter)
            settled(model) { it.viewerChapters?.currChapter?.chapter?.id == 60L }
            val six = model.state.value.viewerChapters!!.currChapter
            model.onPageSelected(loadedPages(six, 2)[1])
            writesLand()
        }
        queued shouldContain 51L
    }

    private suspend fun <T> onFinishedFive(
        slots: Int,
        onEnqueueDelete: (List<tachiyomi.domain.chapter.model.Chapter>) -> Unit = {},
        probe: suspend (MangaReaderViewModelHarness, ReaderViewModel) -> T,
    ): T = MangaReaderViewModelHarness.create().use { harness ->
        val manga = harness.manga(1L, source = 100L, title = "Series")
        harness.chapter(50L, manga, 5.0, scanlator = "x", name = "Chapter 5")
        harness.chapter(51L, manga, 5.0, scanlator = "y", name = "Ch. 5")
        harness.chapter(60L, manga, 6.0, scanlator = "x", name = "Chapter 6")
        harness.open(
            manga,
            chapterId = 50L,
            preferences = mapOf(
                "remove_after_read_slots" to slots,
                "mark_duplicate_read_chapter_read" to setOf(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING),
            ),
            onEnqueueDelete = onEnqueueDelete,
        ) { model, state ->
            val five = state.viewerChapters!!.currChapter
            model.onPageSelected(loadedPages(five, 2)[1])
            writesLand()
            probe(harness, model)
        }
    }

    /** The saves run on the app scope, off the virtual clock. */
    private suspend fun writesLand() = withContext(Dispatchers.Default) { delay(500) }
}
