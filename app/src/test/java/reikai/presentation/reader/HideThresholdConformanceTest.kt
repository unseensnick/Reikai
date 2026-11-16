package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonItems
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonPageTracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * A scroll hides the menu at the same distance, and for the same scrolls, in both readers: the classic long
 * strip's tracker against the rule the novel renderers and the high quality strip feed.
 */
class HideThresholdConformanceTest {

    private val activity = mockk<ReaderActivity>(relaxed = true) {
        every { viewModel.state.value.menuVisible } returns true
    }
    private val tracker = WebtoonPageTracker(activity, WebtoonItems(), THRESHOLD)

    private fun mangaHid(): Boolean = runCatching { verify(exactly = 1) { activity.hideMenu() } }.isSuccess

    @ParameterizedTest
    @CsvSource("5,true,false", "10,true,false", "11,true,true", "-11,true,true", "-5,true,false", "40,false,false")
    fun `a scroll hides the menu past the threshold, and only the reader's own`(
        dy: Float,
        touching: Boolean,
        hides: Boolean,
    ) {
        tracker.onScrolledBy(dy, 0, 0, touching)

        (mangaHid() to MenuHideOnScroll(THRESHOLD).hides(dy, touching)) shouldBe (hides to hides)
    }

    @Test
    fun `the fling a drag lets go into hides the menu in the long strip`() {
        tracker.onScrolledBy(5f, 0, 0, touching = true)
        tracker.onScrolledBy(40f, 0, 0, touching = false)

        mangaHid() shouldBe true
    }

    private companion object {
        const val THRESHOLD = 10
    }
}
