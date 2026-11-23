package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * A manga first open that fails after the series is known but before its chapter lists are built. The
 * failure dialog asks for the chapter's web page then, so that lookup must answer rather than crash.
 * Manga only: the novel reader looks a chapter up in its repository, which has no unbuilt state.
 */
class MangaReaderInitFailureTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `a chapter missing from its series fails the open without crashing the web page lookup`() = runTest {
        MangaReaderViewModelHarness.create().use { harness ->
            val manga = harness.manga(1L, source = 100L, title = "Series")
            harness.chapter(10L, manga, 1.0)
            harness.open(manga, chapterId = 99L, allowInitError = true) { model, _ ->
                val provider = MangaReaderProvider(
                    viewModel = model,
                    readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
                    downloadManager = mockk(relaxed = true),
                    titleWords = EnglishChapterTitleWords,
                )
                provider.chapterWebUrl(99L)
            }
        } shouldBe null
    }
}
