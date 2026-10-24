package reikai.domain.novel

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Each layout's inversion rules, which both readers answer with. How a layout pick applies them is pinned for
 * both readers by `TapLayoutPickConformanceTest`.
 */
class NovelTapLayoutTest {

    @Test
    fun `each layout offers the inversions that change it`() {
        NovelTapLayout.entries.associateWith { it.invertModes } shouldBe NovelTapLayout.entries.associateWith {
            when (it) {
                NovelTapLayout.CENTER, NovelTapLayout.CENTER_LARGE -> listOf(TappingInvertMode.NONE)
                NovelTapLayout.BOTTOM -> listOf(TappingInvertMode.NONE, TappingInvertMode.VERTICAL)
                else -> TappingInvertMode.entries
            }
        }
    }

    @Test
    fun `an inversion the layout draws is its own nearest`() {
        NovelTapLayout.KINDLISH.nearestInvert(TappingInvertMode.HORIZONTAL) shouldBe TappingInvertMode.HORIZONTAL
    }

    @Test
    fun `the bottom layout's nearest to both is vertical`() {
        NovelTapLayout.BOTTOM.nearestInvert(TappingInvertMode.BOTH) shouldBe TappingInvertMode.VERTICAL
    }

    @Test
    fun `the bottom layout's nearest to horizontal is none`() {
        NovelTapLayout.BOTTOM.nearestInvert(TappingInvertMode.HORIZONTAL) shouldBe TappingInvertMode.NONE
    }

    @Test
    fun `a centre layout's nearest to any inversion is none`() {
        NovelTapLayout.CENTER_LARGE.nearestInvert(TappingInvertMode.BOTH) shouldBe TappingInvertMode.NONE
    }
}
