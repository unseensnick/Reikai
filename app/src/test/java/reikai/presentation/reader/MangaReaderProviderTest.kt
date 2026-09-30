package reikai.presentation.reader

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.ReaderPosition
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Duration.Companion.seconds

/** How manga reports a chapter it could not open, and whether the reader can stay open after it. */
class MangaReaderProviderTest {

    private val failed = Chapter.create().copy(id = 5L)

    private fun provider(
        state: ReaderViewModel.State,
        viewModel: ReaderViewModel = mockk(relaxed = true),
        downloadManager: DownloadManager = mockk(relaxed = true),
    ) = MangaReaderProvider(
        viewModel = viewModel.also { every { it.state } returns MutableStateFlow(state) },
        readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        downloadManager = downloadManager,
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

    /** The emission names its chapter; the session may already have moved past it when the map runs. */
    @Test
    fun `the web address is built for the chapter the state names`() = runTest {
        val viewModel = mockk<ReaderViewModel>(relaxed = true) {
            every { getChapterUrl(7L) } returns "https://site.example/7"
        }
        val state = ReaderViewModel.State(
            viewerChapters = ViewerChapters(
                ReaderChapter(Chapter.create().copy(id = 7L).toDbChapter()),
                prevChapter = null,
                nextChapter = null,
            ),
        )

        provider(state, viewModel).webUrl.first() shouldBe "https://site.example/7"
    }

    /** A paged auto-scroll counts from here, so a page still downloading must not use up its time. */
    @Test
    fun `the page on screen is ready only once its image is`() = runTest {
        val page = ReaderPage(0, "/7/0", "https://img/0").apply { status = Page.State.DownloadImage }

        provider(showing(page)).shownPage.first().ready shouldBe false
    }

    @Test
    fun `a page whose image arrived is ready`() = runTest {
        val page = ReaderPage(0, "/7/0", "https://img/0").apply { status = Page.State.Ready }

        provider(showing(page)).shownPage.first().ready shouldBe true
    }

    /** A paged scroll waits on a failed page until its retry lands, rather than turning past it. */
    @Test
    fun `a failed page is not ready`() = runTest {
        val page = ReaderPage(0, "/7/0", "https://img/0").apply { status = Page.State.Error(Exception()) }

        provider(showing(page)).shownPage.first().ready shouldBe false
    }

    /**
     * A reload swaps the same chapter's pages for new ones at the same index, and the model's loading
     * flag moving is the state change that carries it. The countdown has to start over on the new page.
     */
    @Test
    fun `a reloaded chapter is a new page`() = runTest {
        val chapter = ReaderChapter(Chapter.create().copy(id = 7L).toDbChapter())
        val state = MutableStateFlow(showing(ReaderPage(0, "/7/0", "https://img/0"), chapter))
        val seen = mutableListOf<ShownPage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            providerOver(state).shownPage.toList(seen)
        }

        chapter.state = ReaderChapter.State.Loaded(listOf(ReaderPage(0, "/7/0", "https://img/0")))
        state.value = state.value.copy(isLoadingAdjacentChapter = true)

        seen.map { it.key }.distinct().size shouldBe 2
    }

    /** A failed download stays in the queue, so the queue itself does not emit; only its status moves. */
    @Test
    fun `a chapter row follows its download failing`() = runTest {
        val chapter = Chapter.create().copy(id = 7L)
        val download = Download(mockk(), Manga.create(), chapter).apply { status = Download.State.DOWNLOADING }
        val statusChanges = MutableSharedFlow<Download>()
        val downloadManager = mockk<DownloadManager> {
            every { queueState } returns MutableStateFlow(listOf(download))
            every { statusFlow() } returns statusChanges
            every { progressFlow() } returns emptyFlow()
        }
        val viewModel = mockk<ReaderViewModel>(relaxed = true) {
            every { getChapters() } returns listOf(ReaderChapterItem(chapter, sourceName = null))
        }
        val rows = provider(ReaderViewModel.State(), viewModel, downloadManager).chapterList.rows
        // The rows are built on the IO dispatcher, so they are awaited in real time, bounded.
        val failedRow = backgroundScope.async(Dispatchers.Default) {
            withTimeoutOrNull(WAIT) { rows.first { it.single().downloadState == Download.State.ERROR } }
        }
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(WAIT) { statusChanges.subscriptionCount.first { it > 0 } }
        }

        download.status = Download.State.ERROR
        statusChanges.emit(download)

        failedRow.await()?.single()?.downloadState shouldBe Download.State.ERROR
    }

    private fun showing(
        page: ReaderPage,
        chapter: ReaderChapter = ReaderChapter(Chapter.create().copy(id = 7L).toDbChapter()),
    ): ReaderViewModel.State {
        chapter.state = ReaderChapter.State.Loaded(listOf(page))
        return ReaderViewModel.State(
            viewerChapters = ViewerChapters(chapter, prevChapter = null, nextChapter = null),
            position = ReaderPosition(7L, ChapterProgress.Pages(lastPageRead = 0L, pageCount = 1L)),
        )
    }

    private fun providerOver(state: MutableStateFlow<ReaderViewModel.State>) = MangaReaderProvider(
        viewModel = mockk(relaxed = true) { every { this@mockk.state } returns state },
        readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        downloadManager = mockk(relaxed = true),
        titleWords = EnglishChapterTitleWords,
    )

    private companion object {
        val WAIT = 3.seconds
    }
}
