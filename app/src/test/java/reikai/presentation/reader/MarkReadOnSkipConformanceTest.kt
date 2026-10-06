package reikai.presentation.reader

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Mark chapter read when skipping ahead marks the chapter a Next step leaves, and only once the step has
 * landed, since a failed load leaves the reader in it. A Previous, the setting off and a source in
 * incognito each leave it unread. Pinned once over both readers.
 */
class MarkReadOnSkipConformanceTest {

    /** How the reader steps away from the chapter it opened on, in an entry listing chapters 1 and 2. */
    data class Step(
        val forward: Boolean = true,
        val loads: Boolean = true,
        val markOnSkip: Boolean = true,
        val incognito: Boolean = false,
    )

    interface Probe {
        /** Takes [step] and answers whether the chapter it left is stored read. */
        suspend fun leftChapterRead(scope: TestScope, step: Step): Boolean?
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a Next that lands marks the chapter left read`(probe: Probe) = runTest {
        probe.leftChapterRead(this, Step()) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a Next whose chapter fails to load leaves the chapter unread`(probe: Probe) = runTest {
        probe.leftChapterRead(this, Step(loads = false)) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a Next with the setting off leaves the chapter unread`(probe: Probe) = runTest {
        probe.leftChapterRead(this, Step(markOnSkip = false)) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a Previous leaves the chapter unread`(probe: Probe) = runTest {
        probe.leftChapterRead(this, Step(forward = false)) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a Next from a source in incognito leaves the chapter unread`(probe: Probe) = runTest {
        probe.leftChapterRead(this, Step(incognito = true)) shouldBe false
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun leftChapterRead(scope: TestScope, step: Step): Boolean? =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                harness.chapter(20L, manga, 2.0)
                val (left, target) = if (step.forward) 10L to 20L else 20L to 10L
                harness.open(
                    manga,
                    chapterId = left,
                    preferences = mapOf("mark_read_on_skip" to step.markOnSkip),
                    failing = if (step.loads) emptySet() else setOf(target),
                    incognito = step.incognito,
                ) { model, _ ->
                    // The mark is written on the IO dispatcher, which virtual time does not reach, so
                    // what the step launched is waited out before the read.
                    val scopeJob = model.viewModelScope.coroutineContext.job
                    val running = scopeJob.children.toSet()
                    if (step.forward) model.loadNextChapter() else model.loadPreviousChapter()
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { (scopeJob.children.toSet() - running).joinAll() }
                    }
                    harness.stored(left).read
                }
            }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun leftChapterRead(scope: TestScope, step: Step): Boolean? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                harness.novelPreferences.readerMarkReadOnSkip().set(step.markOnSkip)
                val source = harness.source("src")
                val novel = harness.novel(source)
                val first = harness.chapter(novel, 1.0)
                val second = harness.chapter(novel, 2.0)
                val (left, target) = if (step.forward) first to second else second to first
                if (!step.loads) source.failing += target.url
                if (step.incognito) harness.incognito(source)
                val model = harness.open(novel, left.id)
                scope.advanceUntilIdle()

                if (step.forward) model.nextChapter() else model.previousChapter()
                scope.advanceUntilIdle()

                harness.isRead(left)
            }
    }
}
