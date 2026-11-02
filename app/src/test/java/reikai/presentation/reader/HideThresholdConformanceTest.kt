package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonItems
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonPageTracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** A finger scroll hides the menu at the same distance in both readers: one case set over both halves. */
class HideThresholdConformanceTest {

    private fun mangaHides(dy: Float): Boolean {
        val activity = mockk<ReaderActivity>(relaxed = true) {
            every { viewModel.state.value.menuVisible } returns true
        }
        val items = WebtoonItems()
        WebtoonPageTracker(activity, items, THRESHOLD).onScrolledBy(dy, 0, 0)
        return runCatching { verify(exactly = 1) { activity.hideMenu() } }.isSuccess
    }

    @ParameterizedTest
    @CsvSource("5,false", "10,false", "11,true", "-11,true", "-5,false")
    fun `a scroll hides the menu past the threshold`(dy: Float, hides: Boolean) {
        (mangaHides(dy) to scrollHidesMenu(dy, THRESHOLD)) shouldBe (hides to hides)
    }

    private companion object {
        const val THRESHOLD = 10
    }
}
