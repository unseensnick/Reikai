package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelTapLayout

/** The tap-zone rules both the settings screen and the reader's own sheet read. */
class NovelTapZonesBoundsTest {

    @Test
    fun `the inversion choice is offered only where an inversion changes the zones`() {
        NovelTapLayout.entries.filter { it.offersInvert } shouldBe NovelTapLayout.entries - setOf(
            NovelTapLayout.DISABLED,
            NovelTapLayout.CENTER,
            NovelTapLayout.CENTER_LARGE,
        )
    }

    @Test
    fun `a stored bottom zone height outside the setting's range is held to its ends`() {
        listOf(1, 30, 80).map {
            NovelTapZones(NovelTapLayout.BOTTOM, TappingInvertMode.NONE, it).bottomZoneFraction
        } shouldBe
            listOf(0.02f, 0.3f, 0.5f)
    }
}
