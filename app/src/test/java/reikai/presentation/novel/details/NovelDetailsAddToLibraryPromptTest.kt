package reikai.presentation.novel.details

import androidx.compose.material3.SnackbarData
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import io.kotest.matchers.nulls.shouldBeNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers

/**
 * The add-to-library prompt after a novel's first download is asked once per screen, as on manga, even
 * though the novel page rebuilds its state whenever its chapter list changes.
 */
class NovelDetailsAddToLibraryPromptTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `a second download after the list rebuilt asks nothing`() = runTest {
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novelId = harness.novel(harness.source("alpha"), inLibrary = false)
            harness.chapter(novelId, 1.0)
            harness.chapter(novelId, 2.0)
            val model = harness.openDetails(novelId)
            val (first, second) = model.awaitLoaded { it.chapters.size == 2 }.chapters

            model.onChapterDownloadAction(first, ChapterDownloadAction.START)
            model.awaitSnackbar().dismiss()
            model.toggleChapterBookmark(first)
            model.awaitLoaded { loaded -> loaded.chapters.any { it.id == first.id && it.bookmark } }
            model.onChapterDownloadAction(second, ChapterDownloadAction.START)
            advanceTimeBy(PROMPT_WINDOW_MS)
            runCurrent()

            model.snackbarHostState.currentSnackbarData.shouldBeNull()
        }
    }

    private suspend fun NovelDetailsViewModel.awaitLoaded(
        predicate: (NovelDetailsState.Loaded) -> Boolean,
    ): NovelDetailsState.Loaded = withTimeout(10_000) {
        state.filterIsInstance<NovelDetailsState.Loaded>().first(predicate)
    }

    private suspend fun NovelDetailsViewModel.awaitSnackbar(): SnackbarData =
        withTimeoutOrNull(10_000) {
            var data = snackbarHostState.currentSnackbarData
            while (data == null) {
                delay(20)
                data = snackbarHostState.currentSnackbarData
            }
            data
        } ?: error("No snackbar shown")

    private companion object {
        // Far beyond the virtual milliseconds the prompt takes to show when it is asked.
        const val PROMPT_WINDOW_MS = 1_500L
    }
}
