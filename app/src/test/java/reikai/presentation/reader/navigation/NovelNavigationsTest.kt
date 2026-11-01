package reikai.presentation.reader.navigation

import androidx.compose.ui.geometry.Offset
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.Action
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelTapLayout
import reikai.presentation.reader.NovelTapAction
import reikai.presentation.reader.NovelTapZones

/** Reikai's own layouts over upstream's regions, which a settings change must not be taken for no change. */
class NovelNavigationsTest {

    private val none = TappingInvertMode.NONE

    @Test
    fun `a tap outside a zone-only layout's zone is left to the page`() {
        BottomNavigation(0.2f, none, false).actionAt(Offset(0.5f, 0.1f)) shouldBe null
    }

    @Test
    fun `a tap inside a zone-only layout's zone opens the menu`() {
        BottomNavigation(0.2f, none, false).actionAt(Offset(0.5f, 0.9f)) shouldBe Action.MENU
    }

    @Test
    fun `a full layout answers a tap outside its zones with the menu`() {
        RightAndLeftNavigation(none, false).actionAt(Offset(0.5f, 0.5f)) shouldBe Action.MENU
    }

    @Test
    fun `a Bottom zone of another height is another layout`() {
        BottomNavigation(0.2f, none, false) shouldNotBe BottomNavigation(0.3f, none, false)
    }

    @Test
    fun `a Bottom zone of the same height is the same layout`() {
        BottomNavigation(0.2f, none, false) shouldBe BottomNavigation(0.2f, none, false)
    }

    @Test
    fun `a large Center is another layout`() {
        CenterNavigation(large = true, none, false) shouldNotBe CenterNavigation(large = false, none, false)
    }

    @Test
    fun `a novel's right edge reads on when its pages run left to right`() {
        zones().actionAt(0.9f, 0.5f, rtl = false) shouldBe NovelTapAction.FORWARD
    }

    @Test
    fun `a novel's right edge reads back when its pages run right to left`() {
        zones().actionAt(0.9f, 0.5f, rtl = true) shouldBe NovelTapAction.BACK
    }

    private fun zones() = NovelTapZones(NovelTapLayout.RIGHT_AND_LEFT, none, bottomZoneHeightPercent = 20)
}
