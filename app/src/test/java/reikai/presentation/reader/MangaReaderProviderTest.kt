package reikai.presentation.reader

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter

/** How manga reports a chapter it could not open, and whether the reader can stay open after it. */
class MangaReaderProviderTest {

    private val failed = Chapter.create().copy(id = 5L)

    private fun provider(state: ReaderViewModel.State, viewModel: ReaderViewModel = mockk(relaxed = true)) =
        MangaReaderProvider(
            viewModel = viewModel.also { every { it.state } returns MutableStateFlow(state) },
            readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
            downloadManager = mockk(relaxed = true),
            titleWords = EnglishChapterTitleWords,
        )

    private fun failure(message: String?, fromSource: Boolean = false, attempt: Long = 1L) =
        ReaderViewModel.AdjacentLoadFailure(5L, message, fromSource, attempt)

    /** The chapter on screen is the one the reader kept, not the one to fetch again. */
    @Test
    fun `retrying opens the chapter that failed`() {
        val onScreen = Chapter.create().copy(id = 7L)
        val viewModel = mockk<ReaderViewModel>(relaxed = true) {
            every { getChapters() } returns listOf(onScreen, failed).map { ReaderChapterItem(it, sourceName = null) }
        }
        val state = ReaderViewModel.State(
            viewerChapters = ViewerChapters(
                ReaderChapter(onScreen.toDbChapter()),
                prevChapter = null,
                nextChapter = null,
            ),
            adjacentLoadFailure = failure(null),
        )

        provider(state, viewModel).retryLoad()

        verify { viewModel.loadNewChapterFromDialog(failed) }
    }

    /** Equal failures are one state value, so the second would never reach the reader as a failure. */
    @Test
    fun `a repeat of the same failure reads as a new one`() = runTest {
        val first = provider(ReaderViewModel.State(adjacentLoadFailure = failure("no connection", attempt = 1L)))
        val second = provider(ReaderViewModel.State(adjacentLoadFailure = failure("no connection", attempt = 2L)))

        second.loadState.first() shouldNotBe first.loadState.first()
    }
}
