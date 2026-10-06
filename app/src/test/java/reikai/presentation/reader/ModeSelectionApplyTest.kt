package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Reading mode is manga's alone, since the novel reader has no page direction; rotation is both types'. */
class ModeSelectionApplyTest {

    /** Seeding the pick from the mode in use, right-to-left inherited here, pinned it on every Apply. */
    @Test
    fun `apply with nothing tapped on a series following the default writes nothing`() {
        ModeSelectionApply.modeToApply(picked = null, stored = ReadingMode.DEFAULT) shouldBe null
    }

    @Test
    fun `tapping the inherited mode on a series following the default pins it`() {
        ModeSelectionApply.modeToApply(picked = ReadingMode.RIGHT_TO_LEFT, stored = ReadingMode.DEFAULT) shouldBe
            ReadingMode.RIGHT_TO_LEFT
    }

    @Test
    fun `tapping the mode a series already has writes nothing`() {
        ModeSelectionApply.modeToApply(picked = ReadingMode.LEFT_TO_RIGHT, stored = ReadingMode.LEFT_TO_RIGHT) shouldBe
            null
    }

    /** The rotation picker's grid has no Default tile, so seeding its pick wrote the inherited rotation. */
    @Test
    fun `an untouched rotation apply on a series following the default writes nothing`() {
        ModeSelectionApply.modeToApply(picked = null, stored = ReaderOrientation.DEFAULT) shouldBe null
    }
}
