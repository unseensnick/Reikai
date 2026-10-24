package reikai.presentation.reader.navigation

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelTapLayout

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

    @Test
    fun `one of Mihon's zoned layouts offers every inversion`() {
        MangaTapLayouts.invertModes(1) shouldBe TappingInvertMode.entries
    }
}
