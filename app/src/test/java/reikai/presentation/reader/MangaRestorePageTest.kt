package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers

/**
 * The page a process-death restore and a reload put the reader back on is saved beside the chapter it
 * belongs to. Scrolling onto the next chapter's first page whose switch then fails leaves the reader in
 * the chapter it was in, so the saved page must still be that chapter's.
 */
class MangaRestorePageTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `a page of a neighbour whose switch failed does not replace the saved page`() = runTest {
        MangaReaderViewModelHarness.create().use { harness ->
            val manga = harness.manga(1L, source = 100L, title = "Series")
            harness.chapter(10L, manga, 1.0)
            harness.chapter(20L, manga, 2.0)
            harness.open(manga, chapterId = 10L, failing = setOf(20L)) { model, state ->
                val chapters = state.viewerChapters!!
                harness.settle(model) {
                    model.onPageSelected(loadedPages(chapters.currChapter, 10)[5])
                    model.onPageSelected(loadedPages(chapters.nextChapter!!, 10)[0])
                }
                harness.savedState?.get<Int>("page_index")
            } shouldBe 5
        }
    }

    /** The saved page indexes the copy that was open, which another source can page differently. */
    @Test
    fun `a restore reopens the copy that was open, not another source's copy on disk`() = runTest {
        MangaReaderViewModelHarness.create().use { harness ->
            val online = harness.manga(1L, source = 100L, title = "Online")
            val other = harness.manga(2L, source = 200L, title = "Other")
            val pooled = listOf(harness.chapter(11L, online, 1.0), harness.chapter(21L, other, 1.0))
            val stitch = pooled.mapIndexed { copy, chapter -> ChapterUnit(chapter.id, unit = 0, copyOrder = copy) }
            val group = MergedChapterProvider.Group(
                mangaById = mapOf(online.id to online, other.id to other),
                chapters = pooled.take(1),
                sourceNameByMangaId = mapOf(online.id to "Online", other.id to "Other"),
                stitch = stitch,
                pooledChapters = pooled,
            )
            val restored = harness.open(
                online,
                chapterId = 11L,
                group = group,
                onDisk = setOf(21L),
                restored = 11L to 18,
            ) { _, state ->
                val opened = state.viewerChapters!!.currChapter
                // Stamped by the model's state collector just after the chapter is published.
                advanceUntilIdle()
                opened.chapter.id to opened.requestedPage
            }

            restored shouldBe (11L to 18)
        }
    }
}
