package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.presentation.reader.NovelReaderViewModelHarness
import kotlin.time.Duration.Companion.seconds

/**
 * The novel details model holds its chapter queries open only while its state is collected, plus five
 * seconds, as the manga details model does. A real [NovelDetailsViewModel] over the harness's in-memory
 * database; the harness counts live collectors of the chapter queries. `launchIO` hard-codes
 * Dispatchers.IO, so IO is pointed at the test dispatcher to keep time virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NovelDetailsViewModelSubscriptionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns dispatcher
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing collecting the state leaves the chapter queries unsubscribed`() = runTest(dispatcher) {
        withModel { _, harness ->
            advanceUntilIdle()

            harness.chapterQueries.get() shouldBe 0
        }
    }

    @Test
    fun `a collected state holds the chapter query open`() = runTest(dispatcher) {
        withModel { model, harness ->
            backgroundScope.launch { model.state.collect {} }
            advanceUntilIdle()

            harness.chapterQueries.get() shouldBe 1
        }
    }

    @Test
    fun `the chapter query is still held just under five seconds after the last collector leaves`() =
        runTest(dispatcher) {
            withModel { model, harness ->
                val collector = launch { model.state.collect {} }
                advanceUntilIdle()

                collector.cancel()
                advanceTimeBy(4.9.seconds)
                runCurrent()

                harness.chapterQueries.get() shouldBe 1
            }
        }

    @Test
    fun `the chapter query is released five seconds after the last collector leaves`() = runTest(dispatcher) {
        withModel { model, harness ->
            val collector = launch { model.state.collect {} }
            advanceUntilIdle()

            collector.cancel()
            advanceTimeBy(5.seconds)
            runCurrent()

            harness.chapterQueries.get() shouldBe 0
        }
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

    /** One stored novel with one chapter, opened with nothing collecting its state. */
    private suspend fun TestScope.withModel(
        block: suspend TestScope.(NovelDetailsViewModel, NovelReaderViewModelHarness) -> Unit,
    ) = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
        val novel = harness.novel(harness.source("alpha"))
        harness.chapter(novel, 1.0)
        block(harness.openDetails(novel, shown = false), harness)
    }
}
