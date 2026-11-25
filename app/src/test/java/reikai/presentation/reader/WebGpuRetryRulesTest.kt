package reikai.presentation.reader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.DownloadStream
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.domain.chapter.model.Chapter

class WebGpuRetryRulesTest {

    private val failed = ReaderChapter.State.Error(RuntimeException("offline"))

    /** A failed page decodes only once the loader's own refetch has its file, never while it is in flight. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("retryHolds")
    fun `a failed page waits for its Retry until there is something new to decode`(
        @Suppress("UNUSED_PARAMETER") name: String,
        failedDecoding: Boolean,
        status: Page.State,
        holds: Boolean,
    ) {
        holdsForRetry(failedDecoding, status) shouldBe holds
    }

    @Test
    fun `a neighbour still waiting is loaded on reaching its edge`() {
        shouldAutoPreload(ReaderChapter.State.Wait, isHeld = false) shouldBe true
    }

    @Test
    fun `a neighbour that failed is loaded again after a page turn`() {
        shouldAutoPreload(failed, isHeld = false) shouldBe true
    }

    @Test
    fun `a neighbour that failed is not loaded again until a page turn`() {
        shouldAutoPreload(failed, isHeld = true) shouldBe false
    }

    @Test
    fun `a loaded neighbour is not loaded again`() {
        shouldAutoPreload(ReaderChapter.State.Loaded(emptyList()), isHeld = false) shouldBe false
    }

    @Test
    fun `with transition pages off a failed neighbour still leads to one`() {
        showsTransition(alwaysShowChapterTransition = false, neighbour = failed) shouldBe true
    }

    @Test
    fun `with transition pages off a waiting neighbour leads straight to its pages`() {
        showsTransition(alwaysShowChapterTransition = false, neighbour = ReaderChapter.State.Wait) shouldBe false
    }

    @Test
    fun `with transition pages on a waiting neighbour still leads to one`() {
        showsTransition(alwaysShowChapterTransition = true, neighbour = ReaderChapter.State.Wait) shouldBe true
    }

    @Test
    fun `with both sides failed the next one is offered first`() {
        val next = chapter(2L, failed)

        chapterToRetry(chapter(1L, failed), next) shouldBe next
    }

    @Test
    fun `a transition page offers the side that failed`() {
        val previous = chapter(1L, failed)

        chapterToRetry(previous, chapter(2L, ReaderChapter.State.Loaded(emptyList()))) shouldBe previous
    }

    @Test
    fun `a transition page with nothing failed offers no retry`() {
        chapterToRetry(chapter(1L, ReaderChapter.State.Loading), chapter(2L, ReaderChapter.State.Wait)) shouldBe null
    }

    /** Without one, the forced refetch downloads out of sight and the page stays a ring until it ends. */
    @Test
    fun `a retried page reaches the loader already carrying the stream it renders from`() {
        val loader = RecordingLoader()

        requeueForRetry(ReaderPage(0), loader)

        loader.streamAtRetry shouldNotBe null
    }

    @Test
    fun `an error page that replaces the streaming preview takes the preview with it`() {
        val preview = Any()

        previewAfterError(preview, shown = preview) shouldBe null
    }

    @Test
    fun `an error page over another image keeps the streaming preview`() {
        val preview = Any()

        previewAfterError(preview, shown = Any()) shouldBe preview
    }

    /** The last tap retried [lastTap]; a centre tap ([onlyNew]) on a failure it already retried opens the menu. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("tapRetries")
    fun `a tap retries the failures on screen only when one is new to a centre tap`(
        @Suppress("UNUSED_PARAMETER") name: String,
        lastTap: Set<String>,
        onScreen: Set<String>,
        onlyNew: Boolean,
        retried: Set<String>,
    ) {
        val retries = TapRetries<String>().apply { take(lastTap, onlyNew = false) }

        retries.take(onScreen, onlyNew) shouldBe retried
    }

    @Test
    fun `a failure that cleared before a tap is new when it fails again`() {
        val retries = TapRetries<String>()
        retries.take(setOf("next"), onlyNew = true)
        retries.take(emptySet(), onlyNew = true)

        retries.take(setOf("next"), onlyNew = true) shouldBe setOf("next")
    }

    private class RecordingLoader : PageLoader() {
        override var isLocal = false
        var streamAtRetry: DownloadStream? = null

        override suspend fun getPages(): List<ReaderPage> = emptyList()

        override fun retryPage(page: ReaderPage) {
            streamAtRetry = page.downloadStream
        }
    }

    private fun chapter(id: Long, state: ReaderChapter.State): ReaderChapter {
        return ReaderChapter(Chapter.create().copy(id = id)).also { it.state = state }
    }

    companion object {
        @JvmStatic
        fun retryHolds() = listOf(
            Arguments.of("a page the loader fetched after failing decodes", false, Page.State.Ready, false),
            Arguments.of("a page still failed waits", false, Page.State.Error(Exception("503")), true),
            Arguments.of("a page queued for its refetch waits", false, Page.State.Queue, true),
            Arguments.of("a page whose refetch is loading waits", false, Page.State.LoadPage, true),
            Arguments.of("a page whose image is downloading waits", false, Page.State.DownloadImage, true),
            Arguments.of("a page that failed decoding waits though its file is there", true, Page.State.Ready, true),
        )

        @JvmStatic
        fun tapRetries() = listOf(
            Arguments.of("a first failure is retried", emptySet<String>(), setOf("next"), true, setOf("next")),
            Arguments.of(
                "one the last tap retried opens the menu",
                setOf("next"),
                setOf("next"),
                true,
                emptySet<String>(),
            ),
            Arguments.of(
                "a new one retries every one",
                setOf("next"),
                setOf("next", "page"),
                true,
                setOf("next", "page"),
            ),
            Arguments.of("a tap outside every zone retries again", setOf("next"), setOf("next"), false, setOf("next")),
            Arguments.of(
                "nothing failed retries nothing",
                emptySet<String>(),
                emptySet<String>(),
                true,
                emptySet<String>(),
            ),
        )
    }
}
