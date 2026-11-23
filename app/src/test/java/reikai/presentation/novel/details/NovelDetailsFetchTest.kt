package reikai.presentation.novel.details

import android.content.Context
import androidx.compose.material3.SnackbarData
import androidx.compose.ui.graphics.Color
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.coil.seedColor
import reikai.domain.entry.CoverColors
import reikai.domain.entry.EntryId
import reikai.domain.entry.signedKey
import reikai.domain.novel.interactor.FilterNovelChaptersForDownload
import reikai.domain.novel.model.NovelChapterFlags
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import reikai.novel.install.LnPluginLoadFailure
import reikai.presentation.details.EntrySourceState
import reikai.presentation.reader.FakeNovelSource
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the novel details screen fetches from its source on its own: the first fetch of a novel opened
 * from Browse, a paged source's empty page, and what each shows when the source cannot answer.
 */
class NovelDetailsFetchTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
        // The harness context is a relaxed mock, so the one message a test names is given a text of its own.
        mockkStatic(LOCALIZE)
        every { any<Context>().stringResource(MR.strings.no_chapters_error) } returns NO_CHAPTERS
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(LOCALIZE)
        CoverColors.clear()
        resetVirtualDispatchers()
    }

    @Test
    fun `a novel opened from browsing fills in its stored row and stays outside the library`() = runTest {
        val loaded = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val source = harness.source("alpha")
            source.details = { SourceNovel(path = NEW_URL, name = "New", chapters = listOf(ChapterItem("One", "/1"))) }
            harness.openListing("alpha", NEW_URL).awaitLoaded { it.chapters.size == 1 && it.novel.title == "New" }
        }

        (loaded.novel.title to loaded.novel.favorite) shouldBe ("New" to false)
    }

    @Test
    fun `a novel opened from browsing shows it is refreshing while its first fetch runs`() = runTest {
        val refreshing = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val answer = CompletableDeferred<Unit>()
            harness.source("alpha").details = {
                answer.await()
                SourceNovel(path = NEW_URL, chapters = listOf(ChapterItem("One", "/1")))
            }
            val model = harness.openListing("alpha", NEW_URL)
            try {
                model.awaitLoaded { it.isRefreshing }.isRefreshing
            } finally {
                answer.complete(Unit)
            }
        }

        refreshing shouldBe true
    }

    @Test
    fun `a pull to refresh during the first fetch asks the source nothing more`() = runTest {
        val asked = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val answer = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            harness.source("alpha").details = {
                calls.incrementAndGet()
                answer.await()
                SourceNovel(path = NEW_URL, chapters = listOf(ChapterItem("One", "/1")))
            }
            val model = harness.openListing("alpha", NEW_URL)
            model.awaitLoaded { it.isRefreshing }
            model.refresh()
            answer.complete(Unit)
            model.awaitLoaded { it.chapters.size == 1 && !it.isRefreshing }
            passRefetchWindow()
            calls.get()
        }

        asked shouldBe 1
    }

    @Test
    fun `a novel id with no stored row shows the not-found page`() = runTest {
        val state = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.openDetails(MISSING_ID).awaitState { it == NovelDetailsState.NotFound }
        }

        state shouldBe NovelDetailsState.NotFound
    }

    @Test
    fun `a novel opened from browsing whose plugin is not installed shows its row with the source missing`() =
        runTest {
            val loaded = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
                harness.openListing("gone", NEW_URL).awaitLoaded { it.sourceState == EntrySourceState.Missing }
            }

            loaded.novel.url shouldBe NEW_URL
        }

    @Test
    fun `pulling to refresh a novel whose plugin threw while loading loads the plugin again`() = runTest {
        val loaded = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.pluginFailures.value = mapOf(
                "https://example.org/gone.js" to LnPluginLoadFailure(
                    url = "https://example.org/gone.js",
                    pluginId = "gone",
                    name = "Gone plugin",
                    iconUrl = null,
                    lang = null,
                    version = "1.0.0",
                    customCssUrl = null,
                    reason = LnPluginLoadFailure.Reason.Failed("ReferenceError: x", "trace"),
                ),
            )
            harness.onPluginRetry = { id ->
                harness.source(id).details =
                    { SourceNovel(path = NEW_URL, chapters = listOf(ChapterItem("One", "/1"))) }
            }
            val model = harness.openListing("gone", NEW_URL)
            // The first fetch refuses a pull while its spinner shows
            model.awaitLoaded { it.sourceState == EntrySourceState.Missing && !it.isRefreshing }
            model.refresh()
            model.awaitLoaded { it.chapters.size == 1 }
        }

        loaded.chapters.map { it.url } shouldBe listOf("/1")
    }

    /** The update names the failed load for the same novel, so a pull to refresh must not call it not installed. */
    @Test
    fun `refreshing a stored novel whose plugin threw while loading says why`() = runTest {
        val message = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.pluginFailures.value = mapOf(
                "https://example.org/gone.js" to LnPluginLoadFailure(
                    url = "https://example.org/gone.js",
                    pluginId = "gone",
                    name = "Gone plugin",
                    iconUrl = null,
                    lang = null,
                    version = "1.0.0",
                    customCssUrl = null,
                    reason = LnPluginLoadFailure.Reason.Failed("ReferenceError: x", "trace"),
                ),
            )
            val novel = harness.novel(FakeNovelSource("gone", "unregistered"))
            harness.chapter(novel, 1.0)
            val model = harness.openDetails(novel)
            model.awaitLoaded { it.sourceState == EntrySourceState.Missing }
            model.refresh()
            model.snackbarWithin(10_000)?.visuals?.message
        }

        message?.contains("ReferenceError: x") shouldBe true
    }

    @Test
    fun `a novel opened from browsing whose source lists no chapters says so`() = runTest {
        val shown = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.source("alpha").details = { SourceNovel(path = NEW_URL, name = "New") }
            harness.openListing("alpha", NEW_URL).snackbarWithin(10_000)?.visuals?.message
        }

        shown shouldBe NO_CHAPTERS
    }

    @Test
    fun `a stored novel with no chapters whose source cannot answer says why`() = runTest {
        val message = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val source = harness.source("alpha")
            source.details = { throw IOException("no connection") }
            harness.openDetails(harness.novel(source)).snackbarWithin(10_000)?.visuals?.message
        }

        message shouldBe "no connection"
    }

    /** The snackbar stays up until dismissed, so a fetch that waited on it would hold the spinner as long. */
    @Test
    fun `a failed first fetch stops refreshing while its snackbar shows`() = runTest {
        val refreshing = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val source = harness.source("alpha")
            source.details = { throw IOException("no connection") }
            val model = harness.openDetails(harness.novel(source))
            model.snackbarWithin(10_000)
            model.awaitLoaded { !it.isRefreshing }.isRefreshing
        }

        refreshing shouldBe false
    }

    @Test
    fun `an empty page is asked of the source once however often the list rebuilds`() = runTest {
        val asked = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val source = harness.source("alpha")
            val (novel, model) = harness.openPaged(source)
            model.selectPage(1)
            withTimeout(10_000) { while ("2" !in source.pagesAsked) delay(20) }
            // Any input rebuilds the list; a rename is one the rebuilt page shows.
            harness.updateNovel(novel) { title = "Renamed" }
            model.awaitLoaded { it.novel.title == "Renamed" }
            passRefetchWindow()
            source.pagesAsked.toList()
        }

        asked shouldContainExactly listOf("2")
    }

    @Test
    fun `a page whose source cannot answer says why`() = runTest {
        val message = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val source = harness.source("alpha").apply { failingPages += "2" }
            val (_, model) = harness.openPaged(source)
            model.selectPage(1)
            model.snackbarWithin(10_000)?.visuals?.message
        }

        message shouldBe "no connection"
    }

    @Test
    fun `a page that failed is asked again when it is picked again`() = runTest {
        val asked = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val source = harness.source("alpha").apply { failingPages += "2" }
            val (_, model) = harness.openPaged(source)
            model.selectPage(1)
            model.snackbarWithin(10_000)
            model.selectPage(0)
            model.awaitLoaded { it.pageIndex == 0 }
            model.selectPage(1)
            withTimeout(10_000) { while (source.pagesAsked.size < 2) delay(20) }
            source.pagesAsked.toList()
        }

        asked shouldContainExactly listOf("2", "2")
    }

    @Test
    fun `a page that failed is asked again when it is picked again while shown`() = runTest {
        val asked = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val source = harness.source("alpha").apply { failingPages += "2" }
            val (_, model) = harness.openPaged(source)
            model.selectPage(1)
            model.snackbarWithin(10_000)
            model.selectPage(1)
            withTimeout(10_000) { while (source.pagesAsked.size < 2) delay(20) }
            source.pagesAsked.toList()
        }

        asked shouldContainExactly listOf("2", "2")
    }

    @Test
    fun `a paged novel shows its pages while its source is still busy with its web address`() = runTest {
        val loaded = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val busy = CompletableDeferred<Unit>()
            val source = harness.source("alpha")
            source.resolve = {
                busy.await()
                null
            }
            source.details = { SourceNovel(path = NEW_URL, name = "New", totalPages = 4) }
            val model = harness.openListing("alpha", NEW_URL)
            try {
                withTimeoutOrNull(10_000) {
                    model.state.filterIsInstance<NovelDetailsState.Loaded>().first { it.pages.size == 4 }
                }
            } finally {
                busy.complete(Unit)
            }
        }

        loaded?.pages?.size shouldBe 4
    }

    @Test
    fun `a paged novel opened from browsing asks for its first page once`() = runTest {
        val asked = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val source = harness.source("alpha")
            source.details = { SourceNovel(path = NEW_URL, name = "New", totalPages = 4) }
            harness.openListing("alpha", NEW_URL).awaitLoaded { it.pages.size == 4 }
            withTimeout(10_000) { while ("1" !in source.pagesAsked) delay(20) }
            passRefetchWindow()
            source.pagesAsked.toList()
        }

        asked shouldContainExactly listOf("1")
    }

    @Test
    fun `a page picked before the plugin loaded is asked for once it has`() = runTest {
        val asked = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val gate = harness.holdPluginLoads()
            val source = harness.source("alpha")
            val novel = harness.novel(source, totalPages = 2)
            harness.chapter(novel, 1.0, page = "1")
            val model = harness.openDetails(novel)
            model.awaitLoaded { it.pages.size == 2 }
            model.selectPage(1)
            model.awaitLoaded { it.pageIndex == 1 }
            gate.complete(Unit)
            model.awaitLoaded { it.sourceName == "alpha" }
            withTimeout(10_000) { while ("2" !in source.pagesAsked) delay(20) }
            passRefetchWindow()
            source.pagesAsked.toList()
        }

        asked shouldContainExactly listOf("2")
    }

    @Test
    fun `an empty page under a chapter filter is not asked of the source`() = runTest {
        val asked = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.novelPreferences.defaultChapterFilterUnread().set(NovelChapterFlags.SHOW_UNREAD)
            val source = harness.source("alpha")
            val (_, model) = harness.openPaged(source)
            model.selectPage(1)
            model.awaitLoaded { it.pageIndex == 1 }
            passRefetchWindow()
            source.pagesAsked.toList()
        }

        asked.shouldBeEmpty()
    }

    @Test
    fun `a page past the novel's last goes back to the first`() = runTest {
        val (loaded, firstPage) = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val source = harness.source("alpha")
            val novel = harness.novel(source, totalPages = 3)
            val firstPage = harness.chapter(novel, 1.0, page = "1")
            harness.chapter(novel, 2.0, page = "2")
            val model = harness.openDetails(novel)
            model.awaitLoaded { it.pages.size == 3 && it.sourceName == "alpha" }
            model.selectPage(2)
            model.awaitLoaded { it.pageIndex == 2 }
            harness.updateNovel(novel) { totalPages = 2 }
            model.awaitLoaded { it.pages.size == 2 } to firstPage.id
        }

        (loaded.pageIndex to loaded.chapters.map { it.id }) shouldBe (0 to listOf(firstPage))
    }

    /** An update stores and announces every page it reached, while the count it keeps stops below a failed one. */
    @Test
    fun `a page stored past the novel's page count can be picked`() = runTest {
        val pages = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(harness.source("alpha"), totalPages = 2)
            harness.chapter(novel, 1.0, page = "1")
            harness.chapter(novel, 2.0, page = "2")
            harness.chapter(novel, 4.0, page = "4")
            harness.openDetails(novel).awaitLoaded { it.sourceName == "alpha" }.pages
        }

        pages shouldBe listOf("1", "2", "3", "4")
    }

    /** Its second page failed on the first walk, so the count it keeps never rose past one. */
    @Test
    fun `a novel kept at one page with later pages stored shows them all`() = runTest {
        val pages = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(harness.source("alpha"))
            harness.chapter(novel, 1.0, page = "1")
            harness.chapter(novel, 3.0, page = "3")
            harness.openDetails(novel).awaitLoaded { it.sourceName == "alpha" }.pages
        }

        pages shouldBe listOf("1", "2", "3")
    }

    /** The last update failed on page 2 and kept the count at one, so page 2 opened since and holds new chapters. */
    @Test
    fun `a page the last update failed on brings new chapters when details fetches it`() = runTest {
        val filter = mockk<FilterNovelChaptersForDownload>(relaxed = true)
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.filterChaptersForDownload = filter
            val source = harness.source("alpha")
            source.pageChapters["2"] = listOf(ChapterItem("Two", "/2"))
            val novel = harness.novel(source)
            harness.chapter(novel, 1.0, page = "1")
            harness.chapter(novel, 3.0, page = "3")
            val model = harness.openDetails(novel)
            model.awaitLoaded { it.pages.size == 3 && it.sourceName == "alpha" }
            model.selectPage(1)
            withTimeout(10_000) { while ("2" !in source.pagesAsked) delay(20) }
            coVerify(timeout = 10_000) { filter.await(any(), match { new -> new.map { it.url } == listOf("/2") }) }
        }
    }

    @Test
    fun `a novel grouped by labels shows its labels`() = runTest {
        val pages = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(harness.source("alpha"))
            harness.chapter(novel, 1.0, page = "Volume 1")
            harness.chapter(novel, 2.0, page = "Volume 2")
            harness.openDetails(novel).awaitLoaded { it.sourceName == "alpha" }.pages
        }

        pages shouldBe listOf("Volume 1", "Volume 2")
    }

    @Test
    fun `a novel grouped by labels with a numbered one keeps its labels`() = runTest {
        val pages = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(harness.source("alpha"))
            harness.chapter(novel, 1.0, page = "Prologue")
            harness.chapter(novel, 2.0, page = "3")
            harness.openDetails(novel).awaitLoaded { it.sourceName == "alpha" }.pages
        }

        pages shouldBe listOf("Prologue", "3")
    }

    @Test
    fun `the header takes the tint already found for the cover`() = runTest {
        val tint = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(harness.source("alpha"), cover = "https://alpha.example/cover.jpg")
            harness.chapter(novel, 1.0)
            EntryId.Novel(novel).seedColor("https://alpha.example/cover.jpg", null, inLibrary = true) { TINT }
            harness.openDetails(novel).awaitLoaded { it.seedColor != null }.seedColor
        }

        tint shouldBe Color(TINT)
    }

    /** The screen stays open across an edit or a refresh, so a new cover has to reach its tint there. */
    @Test
    fun `an open header takes the tint of a cover that changes under it`() = runTest {
        val tint = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(harness.source("alpha"), cover = "https://alpha.example/cover.jpg")
            harness.chapter(novel, 1.0)
            EntryId.Novel(novel).seedColor("https://alpha.example/cover.jpg", null, inLibrary = true) { TINT }
            val details = harness.openDetails(novel)
            details.awaitLoaded { it.seedColor == Color(TINT) }
            // Found ahead, since a test cannot decode a cover; the screen still has to ask for the new one.
            EntryId.Novel(novel).seedColor("https://alpha.example/new.jpg", null, inLibrary = true) { OTHER_TINT }

            harness.changeCover(novel, "https://alpha.example/new.jpg")

            details.awaitLoaded { it.seedColor == Color(OTHER_TINT) }.seedColor
        }

        tint shouldBe Color(OTHER_TINT)
    }

    @Test
    fun `a stored novel whose plugin is gone shows its chapters and never reads as not found`() = runTest {
        val seen = CopyOnWriteArrayList<NovelDetailsState>()
        val state = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val gate = harness.holdPluginLoads()
            val novel = harness.novel(FakeNovelSource("gone", "unregistered"))
            harness.chapter(novel, 1.0)
            val model = harness.openDetails(novel)
            val watcher = backgroundScope.launch { model.state.collect { seen += it } }
            model.awaitLoaded { true }
            gate.complete(Unit)
            model.awaitLoaded { it.sourceState == EntrySourceState.Missing }.also { watcher.cancel() }
        }

        (state.chapters.size to seen.none { it == NovelDetailsState.NotFound }) shouldBe (1 to true)
    }

    /** A novel spanning two pages, the first stored and the second not yet fetched, shown once its source resolved. */
    private suspend fun NovelReaderViewModelHarness.openPaged(
        source: FakeNovelSource,
    ): Pair<Long, NovelDetailsViewModel> {
        val novel = novel(source, totalPages = 2)
        chapter(novel, 1.0, page = "1")
        val model = openDetails(novel)
        model.awaitLoaded { it.pages.size == 2 && it.sourceName == "alpha" }
        return novel to model
    }

    private suspend fun NovelDetailsViewModel.awaitLoaded(
        predicate: (NovelDetailsState.Loaded) -> Boolean,
    ): NovelDetailsState.Loaded = withTimeout(10_000) {
        state.filterIsInstance<NovelDetailsState.Loaded>().first(predicate)
    }

    private suspend fun NovelDetailsViewModel.snackbarWithin(millis: Long): SnackbarData? =
        withTimeoutOrNull(millis) {
            var data = snackbarHostState.currentSnackbarData
            while (data == null) {
                delay(20)
                data = snackbarHostState.currentSnackbarData
            }
            data
        }

    /** Lets virtual time run past the window a second ask of the source would land in, had one been made. */
    private fun TestScope.passRefetchWindow() {
        advanceTimeBy(REFETCH_WINDOW_MS)
        runCurrent()
    }

    private suspend fun NovelDetailsViewModel.awaitState(
        predicate: (NovelDetailsState) -> Boolean,
    ): NovelDetailsState = withTimeout(10_000) { state.first(predicate) }

    private companion object {
        const val NEW_URL = "/novel/new"
        const val NO_CHAPTERS = "no chapters"
        const val LOCALIZE = "tachiyomi.core.common.i18n.LocalizeKt"
        const val MISSING_ID = 999L
        const val TINT = 0xFF336699.toInt()
        const val OTHER_TINT = 0xFF993366.toInt()

        // Far beyond the virtual milliseconds a second ask takes to land when one is made.
        const val REFETCH_WINDOW_MS = 1_500L
    }
}
