package reikai.presentation.reader.navigation

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.NavigationSettings
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelTapLayout
import tachiyomi.i18n.MR

/** Manga's stored tap-layout index: Mihon's six keep their meaning, and the added ones follow the novel's rules. */
class MangaTapLayoutsTest {

    @Test
    fun `a stored index of Mihon's keeps naming its layout`() {
        MangaTapLayouts.names.take(ReaderPreferences.TapZones.size) shouldBe ReaderPreferences.TapZones
    }

    @Test
    fun `the added layouts follow Mihon's in the novel reader's order`() {
        (6..9).map(MangaTapLayouts::added) shouldBe listOf(
            NovelTapLayout.THIRDS,
            NovelTapLayout.CENTER,
            NovelTapLayout.CENTER_LARGE,
            NovelTapLayout.BOTTOM,
        )
    }

    @Test
    fun `none of Mihon's layouts is an added one`() {
        (0..5).mapNotNull(MangaTapLayouts::added) shouldBe emptyList()
    }

    @Test
    fun `Mihon's Disabled follows the novel reader's Disabled`() {
        MangaTapLayouts.layout(5) shouldBe NovelTapLayout.DISABLED
    }

    /** One rule with the novel reader's, so the two settings offer the same choice for a layout. */
    @Test
    fun `an added layout offers the inversions the novel reader offers for it`() {
        (6..9).map(MangaTapLayouts::invertModes) shouldBe (6..9).map { MangaTapLayouts.added(it)!!.invertModes }
    }

    @Test
    fun `an added layout offers the inversion choice when the novel reader does`() {
        (6..9).map(MangaTapLayouts::offersInvert) shouldBe (6..9).map { MangaTapLayouts.added(it)!!.offersInvert }
    }

    @Test
    fun `disabled offers no inversion`() {
        MangaTapLayouts.offersInvert(5) shouldBe false
    }

    @Test
    fun `Mihon's zoned layouts offer the inversion choice`() {
        (0..4).map(MangaTapLayouts::offersInvert) shouldBe List(5) { true }
    }

    /** Mihon's Right and left is the novel reader's, so it offers what the novel reader offers for it. */
    @Test
    fun `Mihon's right and left offers the inversions the novel reader offers for it`() {
        MangaTapLayouts.invertModes(4) shouldBe NovelTapLayout.RIGHT_AND_LEFT.invertModes
    }

    @Test
    fun `one of Mihon's zoned layouts offers every inversion`() {
        MangaTapLayouts.invertModes(1) shouldBe TappingInvertMode.entries
    }

    /** The viewer draws what the sheet names, so a reordered table cannot draw one layout under another's name. */
    @ParameterizedTest
    @MethodSource("stored")
    fun `a stored index draws the layout the settings sheet names`(index: Int, name: Any, drawn: ViewerNavigation) {
        val zones = NavigationSettings(index, TappingInvertMode.NONE, 30).navigator(false, ::ViewerDefault)

        MangaTapLayouts.names[index] shouldBe name
        zones shouldBe drawn
    }

    private class ViewerDefault(invertMode: TappingInvertMode, isReversed: Boolean) :
        ViewerNavigation(invertMode, isReversed) {
        override val regionList: List<Region> = emptyList()
    }

    companion object {
        private val none = TappingInvertMode.NONE

        @JvmStatic
        fun stored() = listOf(
            Arguments.of(0, MR.strings.label_default, ViewerDefault(none, false)),
            Arguments.of(1, MR.strings.l_nav, LNavigation(none, false)),
            Arguments.of(2, MR.strings.kindlish_nav, KindlishNavigation(none, false)),
            Arguments.of(3, MR.strings.edge_nav, EdgeNavigation(none, false)),
            Arguments.of(4, MR.strings.right_and_left_nav, RightAndLeftNavigation(none, false)),
            Arguments.of(5, MR.strings.disabled_nav, DisabledNavigation(none, false)),
            Arguments.of(6, MR.strings.nav_thirds, ThirdsNavigation(none, false)),
            Arguments.of(7, MR.strings.zoom_start_center, CenterNavigation(large = false, none, false)),
            Arguments.of(8, MR.strings.nav_center_large, CenterNavigation(large = true, none, false)),
            Arguments.of(9, MR.strings.nav_bottom, BottomNavigation(0.3f, none, false)),
        )
    }
}
