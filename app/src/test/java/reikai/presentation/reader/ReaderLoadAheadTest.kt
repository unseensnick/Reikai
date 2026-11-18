package reikai.presentation.reader

import android.os.SystemClock
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.mockk.MockKMatcherScope
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The manga reader preloads the next chapter as soon as the current one opens, unless the device is
 * off Wi-Fi with Load ahead only on Wi-Fi on. The eager preload waits out ChapterRetryCooldown after a
 * failure like the viewers' own preloads do, so a failed one is not asked for again on the next page turn.
 */
class ReaderLoadAheadTest {

    @BeforeEach
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class)
        Dispatchers.resetMain()
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

    @Test
    fun `a failed eager preload holds the viewer's own preload`() = runTest {
        MangaReaderViewModelHarness.create().use { harness ->
            val manga = harness.manga(1L, source = 100L, title = "Series")
            harness.chapter(10L, manga, 1.0)
            harness.chapter(20L, manga, 2.0)
            harness.open(manga, chapterId = 10L, failing = setOf(20L), wifi = true) { model, state ->
                coVerify(timeout = 5_000, exactly = 1) { anyConstructed<ChapterLoader>().loadChapter(next(), any()) }
                // The failure is recorded just after the call returns, on the preload's own thread
                withContext(Dispatchers.Default) { delay(300) }

                model.preload(state.viewerChapters!!.nextChapter!!)

                coVerify(exactly = 1) { anyConstructed<ChapterLoader>().loadChapter(next(), any()) }
            }
        }
    }

    private suspend fun nextChapterLoads(wifi: Boolean, wifiOnly: Boolean, expected: Int) {
        MangaReaderViewModelHarness.create().use { harness ->
            val manga = harness.manga(1L, source = 100L, title = "Series")
            harness.chapter(10L, manga, 1.0)
            harness.chapter(20L, manga, 2.0)
            val preferences = mapOf<String, Any>("pref_reader_load_ahead_only_over_wifi" to wifiOnly)
            harness.open(manga, chapterId = 10L, preferences = preferences, wifi = wifi) { _, _ ->
                // The preload runs on the IO dispatcher, out of virtual time's reach: a load that never comes gets a
                // second to show up, and one that does gets as long as a busy runner needs.
                withContext(Dispatchers.Default) { delay(1_000) }
                coVerify(timeout = 10_000, exactly = expected) {
                    anyConstructed<ChapterLoader>().loadChapter(next(), any())
                }
            }
        }
    }

    private fun MockKMatcherScope.next() = match<ReaderChapter> { it.chapter.id == 20L }
}
