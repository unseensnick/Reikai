package reikai.presentation.novel.details

import androidx.compose.material3.SnackbarData
import androidx.compose.ui.graphics.Color
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.coil.seedColor
import reikai.domain.entry.EntryId
import reikai.domain.entry.signedKey
import reikai.domain.novel.model.NovelChapterFlags
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import reikai.novel.install.LnPluginLoadFailure
import reikai.presentation.details.EntrySourceState
import reikai.presentation.reader.FakeNovelSource
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.domain.manga.model.MangaCover
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the novel details screen fetches from its source on its own: the first fetch of a novel opened
 * from Browse, a paged source's empty page, and what each shows when the source cannot answer.
 */
class NovelDetailsFetchTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() {
        MangaCover.vibrantCoverColorMap.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `a novel opened from browsing is stored outside the library and shown`() = runTest {
        val (loaded, stored) = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val source = harness.source("alpha")
            source.details = { SourceNovel(path = NEW_URL, name = "New", chapters = listOf(ChapterItem("One", "/1"))) }
            val model = harness.openDetails("alpha", NEW_URL)
            model.awaitLoaded { it.chapters.size == 1 } to harness.storedNovel("alpha", NEW_URL)
        }

        (loaded.novel.id to loaded.novel.favorite) shouldBe (stored?.id to false)
    }

    @Test
    fun `a novel opened from browsing whose plugin is not installed fails to load`() = runTest {
        val state = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.openDetails("gone", NEW_URL).awaitState { it is NovelDetailsState.Failed }
        }

        state.shouldBeInstanceOf<NovelDetailsState.Failed>()
    }

    @Test
    fun `a novel whose plugin threw while loading offers to load it again`() = runTest {
        val state = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
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
            harness.openDetails("gone", NEW_URL).awaitState { it is NovelDetailsState.Failed }
        }

        (state as NovelDetailsState.Failed).retryable shouldBe true
    }

    @Test
    fun `a novel opened from browsing whose source cannot answer shows why`() = runTest {
        val state = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.source("alpha").details = { throw IOException("no connection") }
            harness.openDetails("alpha", NEW_URL).awaitState { it is NovelDetailsState.Failed }
        }

        state shouldBe NovelDetailsState.Failed("no connection")
    }

    @Test
    fun `a novel opened from browsing whose source lists no chapters says so`() = runTest {
        val shown = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.source("alpha").details = { SourceNovel(path = NEW_URL, name = "New") }
            harness.openDetails("alpha", NEW_URL).snackbarWithin(10_000)
        }

        (shown != null) shouldBe true
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

    @Test
    fun `an empty page is asked of the source once however often the list rebuilds`() = runTest {
        val asked = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val source = harness.source("alpha")
            val (novel, model) = harness.openPaged(source)
            model.selectPage(1)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while ("2" !in source.pagesAsked) delay(20) }
            }
            // Any input rebuilds the list; a rename is one the rebuilt page shows.
            harness.updateNovel(novel) { title = "Renamed" }
            model.awaitLoaded { it.novel.title == "Renamed" }
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(REFETCH_WINDOW_MS) { while (source.pagesAsked.size < 2) delay(20) }
            }
            source.pagesAsked.toList()
        }

        asked shouldContainExactly listOf("2")
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
            val model = harness.openDetails("alpha", NEW_URL)
            try {
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(10_000) {
                        model.state.filterIsInstance<NovelDetailsState.Loaded>().first { it.pages.size == 4 }
                    }
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
            harness.openDetails("alpha", NEW_URL).awaitLoaded { it.pages.size == 4 }
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while ("1" !in source.pagesAsked) delay(20) }
                withTimeoutOrNull(REFETCH_WINDOW_MS) { while (source.pagesAsked.size < 2) delay(20) }
            }
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
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(REFETCH_WINDOW_MS) { while ("2" !in source.pagesAsked) delay(20) }
            }
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
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(REFETCH_WINDOW_MS) { while (source.pagesAsked.isEmpty()) delay(20) }
            }
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
            harness.chapter(novel, 3.0, page = "3")
            val model = harness.openDetails(novel)
            model.awaitLoaded { it.pages.size == 3 && it.sourceName == "alpha" }
            model.selectPage(2)
            model.awaitLoaded { it.pageIndex == 2 }
            harness.updateNovel(novel) { totalPages = 2 }
            model.awaitLoaded { it.pages.size == 2 } to firstPage.id
        }

        (loaded.pageIndex to loaded.chapters.map { it.id }) shouldBe (0 to listOf(firstPage))
    }

    @Test
    fun `the header takes the tint already found for the cover`() = runTest {
        val tint = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(harness.source("alpha"), cover = "https://alpha.example/cover.jpg")
            harness.chapter(novel, 1.0)
            EntryId.Novel(novel).seedColor("https://alpha.example/cover.jpg", null) { TINT }
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
            EntryId.Novel(novel).seedColor("https://alpha.example/cover.jpg", null) { TINT }
            val details = harness.openDetails(novel)
            details.awaitLoaded { it.seedColor == Color(TINT) }
            // Found ahead, since a test cannot decode a cover; the screen still has to ask for the new one.
            EntryId.Novel(novel).seedColor("https://alpha.example/new.jpg", null) { OTHER_TINT }

            harness.changeCover(novel, "https://alpha.example/new.jpg")

            details.awaitLoaded { it.seedColor == Color(OTHER_TINT) }.seedColor
        }

        tint shouldBe Color(OTHER_TINT)
    }

    @Test
    fun `a stored novel whose plugin is gone shows its chapters and never fails`() = runTest {
        val seen = CopyOnWriteArrayList<NovelDetailsState>()
        val state = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val gate = harness.holdPluginLoads()
            val novel = harness.novel(FakeNovelSource("gone", "unregistered"))
            harness.chapter(novel, 1.0)
            val model = harness.openDetails(novel)
            val watcher = backgroundScope.launch(Dispatchers.Default) { model.state.collect { seen += it } }
            model.awaitLoaded { true }
            gate.complete(Unit)
            model.awaitLoaded { it.sourceState == EntrySourceState.Missing }.also { watcher.cancel() }
        }

        (state.chapters.size to seen.none { it is NovelDetailsState.Failed }) shouldBe (1 to true)
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
    ): NovelDetailsState.Loaded = withContext(Dispatchers.Default) {
        withTimeout(10_000) { state.filterIsInstance<NovelDetailsState.Loaded>().first(predicate) }
    }

    // The first fetch runs on the real IO dispatcher, so the wait is in real time.
    private suspend fun NovelDetailsViewModel.snackbarWithin(millis: Long): SnackbarData? =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(millis) {
                var data = snackbarHostState.currentSnackbarData
                while (data == null) {
                    delay(20)
                    data = snackbarHostState.currentSnackbarData
                }
                data
            }
        }

    private suspend fun NovelDetailsViewModel.awaitState(
        predicate: (NovelDetailsState) -> Boolean,
    ): NovelDetailsState = withContext(Dispatchers.Default) { withTimeout(10_000) { state.first(predicate) } }

    private companion object {
        const val NEW_URL = "/novel/new"
        const val TINT = 0xFF336699.toInt()
        const val OTHER_TINT = 0xFF993366.toInt()

        // Far beyond the milliseconds a second ask takes to land when one is made.
        const val REFETCH_WINDOW_MS = 1_500L
    }
}
