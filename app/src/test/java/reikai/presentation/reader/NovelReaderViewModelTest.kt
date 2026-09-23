package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The novel reader's session rules that only show once a real model is driven, over [NovelReaderViewModelHarness]. */
class NovelReaderViewModelTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun readerTest(block: suspend TestScope.(NovelReaderViewModelHarness) -> Unit) = runTest {
        NovelReaderViewModelHarness.create(testScheduler).use { block(it) }
    }

    /** Reports reach the next chapter by crossing the threshold, which a threshold lowered below the
     *  reader's place never does, so only the setting changing can bring it in. */
    @Test
    fun `lowering Add the next chapter at below the reader's place adds the next chapter`() = readerTest { harness ->
        val source = harness.source("src")
        val novel = harness.novel(source)
        val opened = harness.chapter(novel, 1.0, progressPercent = 50)
        val next = harness.chapter(novel, 2.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()

        harness.novelPreferences.readerAutoLoadNextAt().set(30)
        advanceUntilIdle()

        model.window.value.chapters.map { it.chapterId } shouldBe listOf(opened.id, next.id)
    }

    /** A source-scoped session lists one member's chapters only, and the group is still a merged one. */
    @Test
    fun `a merged novel opened from one source names that source on every chapter`() = readerTest { harness ->
        val first = harness.novel(harness.source("alpha", "Alpha Source"))
        val second = harness.novel(harness.source("beta", "Beta Source"))
        harness.chapter(first, 1.0)
        val opened = harness.chapter(second, 1.0)
        harness.chapter(second, 2.0)
        harness.merge(first, second)
        val model = harness.open(second, opened.id, sourceScoped = true)
        advanceUntilIdle()

        model.chapterRows.first().map { it.subtitle }.distinct() shouldBe listOf("Beta Source")
    }

    /** The opened chapter is on disk, the next is not, the one after is. */
    @Test
    fun `with Downloaded only on the reader steps over a chapter not on disk`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        harness.chapter(novel, 2.0)
        val third = harness.chapter(novel, 3.0)
        harness.download(opened, "one")
        harness.download(third, "three")
        harness.downloadedOnly.set(true)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()

        model.chapterRows.first().map { it.id } shouldBe listOf(opened.id, third.id)
    }

    /** The browser may already be closed when the save lands, so the reader hears of it from the fetcher. */
    @Test
    fun `a page saved as the open chapter in the browser is shown`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()
        harness.download(opened, "<p>From the page</p>")

        harness.pageSaves.emit(opened.id)
        advanceUntilIdle()

        model.chapter.value?.html.orEmpty() shouldContain "From the page"
    }

    /** The browser's page is how a chapter the source cannot serve gets read, so its save opens it. */
    @Test
    fun `a page saved for a chapter that failed to open opens it`() = readerTest { harness ->
        val source = harness.source("src")
        val novel = harness.novel(source)
        val opened = harness.chapter(novel, 1.0)
        val failed = harness.chapter(novel, 2.0)
        source.failing += failed.url
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()
        model.open(failed.id)
        advanceUntilIdle()
        harness.download(failed, "<p>Saved from the page</p>")

        harness.pageSaves.emit(failed.id)
        advanceUntilIdle()

        model.chapter.value?.html.orEmpty() shouldContain "Saved from the page"
    }

    /** Under the switch the reader's own list holds nothing left to fetch, so download-ahead walks past it. */
    @Test
    fun `with Downloaded only on download-ahead still queues the next chapter not on disk`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val second = harness.chapter(novel, 2.0)
        harness.download(opened, "one")
        harness.downloadedOnly.set(true)
        harness.novelPreferences.autoDownloadWhileReading().set(1)
        harness.open(novel, opened.id)
        advanceUntilIdle()

        verify {
            harness.downloadManager.downloadChapters(
                match { chapters ->
                    chapters.map { it.id } ==
                        listOf(second.id)
                },
            )
        }
    }

    /** Both members carry the same chapters, so the stitch draws every one from the member that leads. */
    @Test
    fun `a merged novel whose chapters all come from one source still names it`() = readerTest { harness ->
        val first = harness.novel(harness.source("alpha", "Alpha Source"))
        val second = harness.novel(harness.source("beta", "Beta Source"))
        val opened = harness.chapter(first, 1.0)
        harness.chapter(first, 2.0)
        harness.chapter(second, 1.0)
        harness.chapter(second, 2.0)
        harness.merge(first, second)
        val model = harness.open(first, opened.id)
        advanceUntilIdle()

        model.chapterRows.first().map { it.subtitle }.distinct() shouldBe listOf("Alpha Source")
    }
}
