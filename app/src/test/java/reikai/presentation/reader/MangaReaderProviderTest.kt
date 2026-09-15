package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter

/** How manga reports a chapter it could not open once the reader is already showing one. */
class MangaReaderProviderTest {

    private val failed = Chapter.create().copy(id = 5L)

    private fun provider(state: ReaderViewModel.State, viewModel: ReaderViewModel = mockk(relaxed = true)) =
        MangaReaderProvider(
            viewModel = viewModel.also { every { it.state } returns MutableStateFlow(state) },
            readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
            downloadManager = mockk(relaxed = true),
        )

    /** Upstream only logged it, and the engine's wait on the pick then jumped the reader there later. */
    @Test
    fun `a chapter that failed to open reports the failure`() = runTest {
        val state = ReaderViewModel.State(
            adjacentLoadFailure = ReaderViewModel.AdjacentLoadFailure(5L, "no connection"),
        )

        provider(state).loadState.first() shouldBe ReaderLoadState.Failed("no connection", canKeepReading = false)
    }

    /** The chapter on screen is the one the reader kept, not the one to fetch again. */
    @Test
    fun `retrying opens the chapter that failed`() {
        val viewModel = mockk<ReaderViewModel>(relaxed = true) {
            every { getChapters() } returns listOf(ReaderChapterItem(failed, sourceName = null))
        }
        val state = ReaderViewModel.State(adjacentLoadFailure = ReaderViewModel.AdjacentLoadFailure(5L, null))

        provider(state, viewModel).retryLoad()

        verify { viewModel.loadNewChapterFromDialog(failed) }
    }
}
