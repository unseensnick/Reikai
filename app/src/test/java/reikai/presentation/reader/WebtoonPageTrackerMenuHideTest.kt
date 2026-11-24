package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonItems
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonPageTracker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/** The classic long strip hides the menu through [MenuHideOnScroll], whose own test pins the rule. */
class WebtoonPageTrackerMenuHideTest {

    private val activity = mockk<ReaderActivity>(relaxed = true) {
        every { viewModel.state.value.menuVisible } returns true
    }

    @Test
    fun `a drag past the strip's threshold hides the menu`() {
        WebtoonPageTracker(activity, WebtoonItems(), hideThreshold = 10).onScrolledBy(11f, 0, 0, touching = true)

        verify(exactly = 1) { activity.hideMenu() }
    }
}
