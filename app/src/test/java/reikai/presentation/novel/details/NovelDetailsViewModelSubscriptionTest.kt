package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers

/**
 * A merged novel's details list follows its siblings' chapter rows live. The subscription window itself is
 * DetailsStateSubscriptionConformanceTest's. IO and Default are pointed at the test dispatcher to keep time
 * virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NovelDetailsViewModelSubscriptionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        virtualDispatchers(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        resetVirtualDispatchers()
    }

    @Test
    fun `a chapter read on a merged sibling reads as read on the All view`() = runTest(dispatcher) {
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val leading = harness.novel(harness.source("alpha"))
            val other = harness.novel(harness.source("beta"))
            harness.chapter(leading, 6.0)
            val otherCopy = harness.chapter(other, 6.0)
            harness.merge(leading, other)
            val model = harness.openDetails(leading, shown = false)
            backgroundScope.launch { model.state.collect {} }
            advanceUntilIdle()

            harness.markRead(otherCopy)
            advanceUntilIdle()

            val loaded = model.state.value as NovelDetailsState.Loaded
            val row = loaded.chapters.single()
            (loaded.mergeSources.size to loaded.marks.isRead(row.id, row.read)) shouldBe (2 to true)
        }
    }

    @Test
    fun `showing hidden chapters renders the list again without opening its chapter queries again`() =
        runTest(dispatcher) {
            NovelReaderViewModelHarness.create(testScheduler).use { harness ->
                val leading = harness.novel(harness.source("alpha"))
                val other = harness.novel(harness.source("beta"))
                harness.chapter(leading, 1.0)
                harness.chapter(other, 1.0)
                harness.merge(leading, other)
                val model = harness.openDetails(leading, shown = false)
                backgroundScope.launch { model.state.collect {} }
                advanceUntilIdle()
                val opened = harness.chapterQueryStarts.get()

                model.toggleShowHidden()
                advanceUntilIdle()

                (model.state.value as NovelDetailsState.Loaded).showHidden shouldBe true
                harness.chapterQueryStarts.get() shouldBe opened
            }
        }
}
