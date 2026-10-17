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

    /** One rule with the novel reader's, so the two settings offer the same choice for a layout. */
    @Test
    fun `an added layout offers the inversions the novel reader offers for it`() {
        (6..9).map(MangaTapLayouts::invertModes) shouldBe (6..9).map { MangaTapLayouts.added(it)!!.invertModes }
    }

    @Test
    fun `disabled offers no inversion`() {
        MangaTapLayouts.invertModes(5) shouldBe emptyList()
    }

    @Test
    fun `one of Mihon's zoned layouts offers every inversion`() {
        MangaTapLayouts.invertModes(1) shouldBe TappingInvertMode.entries
    }
}
