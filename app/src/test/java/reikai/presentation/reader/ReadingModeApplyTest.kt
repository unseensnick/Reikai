package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Manga only: the novel reader has no page-direction mode. */
class ReadingModeApplyTest {

    /** Seeding the pick from the mode in use, right-to-left inherited here, pinned it on every Apply. */
    @Test
    fun `apply with nothing tapped on a series following the default writes nothing`() {
        ReadingModeApply.modeToApply(picked = null, stored = ReadingMode.DEFAULT) shouldBe null
    }

    @Test
    fun `tapping the inherited mode on a series following the default pins it`() {
        ReadingModeApply.modeToApply(picked = ReadingMode.RIGHT_TO_LEFT, stored = ReadingMode.DEFAULT) shouldBe
            ReadingMode.RIGHT_TO_LEFT
    }

    @Test
    fun `tapping the mode a series already has writes nothing`() {
        ReadingModeApply.modeToApply(picked = ReadingMode.LEFT_TO_RIGHT, stored = ReadingMode.LEFT_TO_RIGHT) shouldBe
            null
    }
}
