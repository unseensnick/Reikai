package reikai.presentation.reader

import android.os.SystemClock
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.reader.ChapterRetryCooldown.RETRY_COOLDOWN_MS
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A neighbour chapter that failed to load is left alone by the reader's own retries for a while,
 * while the user's Retry, or opening a chapter, ends the wait at once, pinned once over both
 * readers. Without the wait, the manga reader asked the source again on every page turn.
 */
class ChapterRetryCooldownConformanceTest {

    /** What asks for the failed chapter again. */
    enum class Retry {
        /** The reader's own reach for it: a manga viewer's preload, the novel window's warm. */
        Unprompted,

        /** A tap on the failure's Retry. */
        User,

        /** The reader's own reach, after the user opened (here, reloaded) a chapter. */
        UnpromptedAfterOpen,
    }

    /** One reader opened on chapter 1, whose next chapter has just failed to load and would now load. */
    interface Probe {
        /** Whether the [retry], [elapsedMs] after the failure, reached the source. */
        suspend fun retried(scope: TestScope, elapsedMs: Long, retry: Retry): Boolean
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the reader's own retry inside the wait is held`(probe: Probe) = runTest {
        probe.retried(this, elapsedMs = RETRY_COOLDOWN_MS - 1, Retry.Unprompted) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the user's Retry inside the wait runs at once`(probe: Probe) = runTest {
        probe.retried(this, elapsedMs = 0L, Retry.User) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the reader's own retry once the wait is over runs`(probe: Probe) = runTest {
        probe.retried(this, elapsedMs = RETRY_COOLDOWN_MS, Retry.Unprompted) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `opening a chapter ends the wait`(probe: Probe) = runTest {
        probe.retried(this, elapsedMs = 0L, Retry.UnpromptedAfterOpen) shouldBe true
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun retried(scope: TestScope, elapsedMs: Long, retry: Retry): Boolean =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                harness.chapter(20L, manga, 2.0)
                var now = 1_000L
                mockkStatic(SystemClock::class)
                every { SystemClock.elapsedRealtime() } answers { now }
                try {
                    harness.open(manga, chapterId = 10L) { model, state ->
                        val next = state.viewerChapters!!.nextChapter!!
                        val attempts = AtomicInteger()
                        val reloaded = CompletableDeferred<Unit>()
                        var failing = true
                        coEvery { anyConstructed<ChapterLoader>().loadChapter(any(), any()) } answers {
                            val asked = firstArg<ReaderChapter>()
                            if (asked === next) attempts.incrementAndGet() else reloaded.complete(Unit)
                            if (failing) throw IOException("no connection")
                        }
                        model.preload(next)
                        check(attempts.get() == 1) { "the first preload never reached the source" }
                        failing = false
                        now += elapsedMs
                        if (retry == Retry.UnpromptedAfterOpen) {
                            model.reloadChapter(fromSource = false)
                            withContext(Dispatchers.Default) { withTimeout(10_000) { reloaded.await() } }
                        }
                        model.preload(next, userAsked = retry == Retry.User)
                        attempts.get() == 2
                    }
                } finally {
                    unmockkStatic(SystemClock::class)
                }
            }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun retried(scope: TestScope, elapsedMs: Long, retry: Retry): Boolean =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val source = harness.source("alpha")
                val novel = harness.novel(source)
                val opened = harness.chapter(novel, 1.0)
                val next = harness.chapter(novel, 2.0)
                source.failing += next.url
                // Opening warms the next chapter, which fails.
                val model = harness.open(novel, opened.id)
                scope.advanceUntilIdle()
                model.rendererLanded(model.window.value.generation)
                // A chapter that fits on screen brings the next one's edge, and so its failure, into reach.
                model.reportFitsOnScreen(opened.id, fits = true)
                scope.runCurrent()
                checkNotNull(model.window.value.failedNext) { "the next chapter's failure is not drawn" }
                val asked = source.chaptersAsked.count { it == next.url }
                source.failing.clear()
                scope.advanceTimeBy(elapsedMs)
                scope.runCurrent()
                when (retry) {
                    Retry.User -> model.retryBoundary(forward = true)
                    // The reader's own reach for the next chapter, which a change in what fits makes.
                    Retry.Unprompted -> model.reportFitsOnScreen(opened.id, fits = false)
                    // Reloading warms the next chapter again by itself.
                    Retry.UnpromptedAfterOpen -> model.reloadChapter(fromSource = false)
                }
                scope.advanceUntilIdle()
                source.chaptersAsked.count { it == next.url } > asked
            }
    }
}
