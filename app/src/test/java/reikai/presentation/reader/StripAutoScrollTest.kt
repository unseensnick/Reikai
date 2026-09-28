package reikai.presentation.reader

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** The one rule both long-strip viewers hold their smooth auto-scroll by. */
class StripAutoScrollTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a long strip holds only while a page coming into view is loading`(
        @Suppress("UNUSED_PARAMETER") name: String,
        ahead: List<Page.State>,
        holds: Boolean,
    ) {
        holdsStripAutoScroll(ahead) shouldBe holds
    }

    companion object {
        private val failed = Page.State.Error(Exception("no connection"))

        @JvmStatic
        fun cases() = listOf(
            Arguments.of("every page ready scrolls", listOf(Page.State.Ready, Page.State.Ready), false),
            Arguments.of("a queued page holds", listOf(Page.State.Ready, Page.State.Queue), true),
            Arguments.of("a page being looked up holds", listOf(Page.State.Ready, Page.State.LoadPage), true),
            Arguments.of("a downloading page holds", listOf(Page.State.DownloadImage, Page.State.Ready), true),
            // It scrolls into view, where its Retry is, rather than freezing the strip above it.
            Arguments.of("a failed page scrolls", listOf(Page.State.Ready, failed), false),
            Arguments.of("nothing but transitions scrolls", emptyList<Page.State>(), false),
        )
    }
}
