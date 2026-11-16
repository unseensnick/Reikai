package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Both quick mode menus build their selection here, so its starting state is theirs. Reading mode is
 * manga's alone, since the novel reader has no page direction; rotation is both types'.
 */
class ModeSelectionTest {

    /** Seeding the pick from the mode in use, right-to-left inherited here, pinned it on every Apply. */
    @Test
    fun `apply with nothing tapped on a series following the default writes nothing`() {
        ModeSelection<ReadingMode>().toApply(stored = ReadingMode.DEFAULT) shouldBe null
    }

    @Test
    fun `with nothing tapped the mode in use is highlighted`() {
        ModeSelection<ReadingMode>().isChecked(ReadingMode.RIGHT_TO_LEFT, inUse = ReadingMode.RIGHT_TO_LEFT) shouldBe
            true
    }

    @Test
    fun `tapping the inherited mode on a series following the default pins it`() {
        val selection = ModeSelection<ReadingMode>()

        selection.tap(ReadingMode.RIGHT_TO_LEFT)

        selection.toApply(stored = ReadingMode.DEFAULT) shouldBe ReadingMode.RIGHT_TO_LEFT
    }

    @Test
    fun `tapping the mode a series already has writes nothing`() {
        val selection = ModeSelection<ReadingMode>()

        selection.tap(ReadingMode.LEFT_TO_RIGHT)

        selection.toApply(stored = ReadingMode.LEFT_TO_RIGHT) shouldBe null
    }

    @Test
    fun `a tapped tile is highlighted in place of the mode in use`() {
        val selection = ModeSelection<ReaderOrientation>()

        selection.tap(ReaderOrientation.PORTRAIT)

        selection.isChecked(ReaderOrientation.FREE, inUse = ReaderOrientation.FREE) shouldBe false
    }

    @Test
    fun `the tapped tile is highlighted`() {
        val selection = ModeSelection<ReaderOrientation>()

        selection.tap(ReaderOrientation.PORTRAIT)

        selection.isChecked(ReaderOrientation.PORTRAIT, inUse = ReaderOrientation.FREE) shouldBe true
    }
}
