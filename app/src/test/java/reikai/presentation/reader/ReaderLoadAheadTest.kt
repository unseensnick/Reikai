package reikai.presentation.reader

import android.os.SystemClock
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import java.util.concurrent.atomic.AtomicInteger

/**
 * The manga reader preloads the next chapter as soon as the current one opens, unless the device is
 * off Wi-Fi with Load ahead only on Wi-Fi on. The eager preload waits out ChapterRetryCooldown after a
 * failure like the viewers' own preloads do, so a failed one is not asked for again on the next page turn.
 */
class ReaderLoadAheadTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class)
        resetVirtualDispatchers()
    }

    @Test
    fun `on Wi-Fi opening a chapter preloads the next one`() = runTest {
        nextChapterLoads(wifi = true, wifiOnly = true, expected = 1)
    }

    @Test
    fun `off Wi-Fi the next chapter waits for the viewer`() = runTest {
        nextChapterLoads(wifi = false, wifiOnly = true, expected = 0)
    }

    @Test
    fun `off Wi-Fi with the setting off opening a chapter preloads the next one`() = runTest {
        nextChapterLoads(wifi = false, wifiOnly = false, expected = 1)
    }

    // Counted through the loader's own hook: a coVerify while Dispatchers is mocked records its getters.
    @Test
    fun `a failed eager preload holds the viewer's own preload`() = runTest {
        val nextLoads = AtomicInteger()
        MangaReaderViewModelHarness.create().use { harness ->
            val manga = harness.manga(1L, source = 100L, title = "Series")
            harness.chapter(10L, manga, 1.0)
            harness.chapter(20L, manga, 2.0)
            harness.open(
                manga,
                chapterId = 10L,
                failing = setOf(20L),
                wifi = true,
                onLoad = { id, _ -> if (id == 20L) nextLoads.incrementAndGet() },
            ) { model, state ->
                // Settles the eager preload, whose failure is recorded just after its load call returns
                advanceUntilIdle()
                check(nextLoads.get() == 1) { "The eager preload never ran" }

                model.preload(state.viewerChapters!!.nextChapter!!)
                advanceUntilIdle()

                nextLoads.get()
            }
        } shouldBe 1
    }

    private suspend fun TestScope.nextChapterLoads(wifi: Boolean, wifiOnly: Boolean, expected: Int) {
        val nextLoads = AtomicInteger()
        MangaReaderViewModelHarness.create().use { harness ->
            val manga = harness.manga(1L, source = 100L, title = "Series")
            harness.chapter(10L, manga, 1.0)
            harness.chapter(20L, manga, 2.0)
            val preferences = mapOf<String, Any>("pref_reader_load_ahead_only_over_wifi" to wifiOnly)
            harness.open(
                manga,
                chapterId = 10L,
                preferences = preferences,
                wifi = wifi,
                onLoad = { id, _ -> if (id == 20L) nextLoads.incrementAndGet() },
            ) { _, _ -> advanceUntilIdle() }
        }
        nextLoads.get() shouldBe expected
    }
}
