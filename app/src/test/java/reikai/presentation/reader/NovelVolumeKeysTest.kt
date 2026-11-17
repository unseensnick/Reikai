package reikai.presentation.reader

import android.view.KeyEvent
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.Action
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class NovelVolumeKeysTest {

    @Test
    fun `a fraction below the minimum moves a tenth of the screen`() {
        NovelVolumeKeys.scrollFraction(KeyEvent.KEYCODE_VOLUME_DOWN, inverted = false, fraction = 0.05f) shouldBe 0.1f
    }

    @Test
    fun `a fraction above a screen moves one screen`() {
        NovelVolumeKeys.scrollFraction(KeyEvent.KEYCODE_VOLUME_DOWN, inverted = false, fraction = 1.5f) shouldBe 1f
    }

    @Test
    fun `volume down reads on`() {
        NovelVolumeKeys.scrollFraction(KeyEvent.KEYCODE_VOLUME_DOWN, inverted = false, fraction = 0.5f) shouldBe 0.5f
    }

    @Test
    fun `volume up reads back`() {
        NovelVolumeKeys.scrollFraction(KeyEvent.KEYCODE_VOLUME_UP, inverted = false, fraction = 0.5f) shouldBe -0.5f
    }

    @Test
    fun `inverted volume down reads back`() {
        NovelVolumeKeys.scrollFraction(KeyEvent.KEYCODE_VOLUME_DOWN, inverted = true, fraction = 0.5f) shouldBe -0.5f
    }

    @Test
    fun `page down is not a volume key`() {
        NovelVolumeKeys.isVolumeKey(KeyEvent.KEYCODE_PAGE_DOWN) shouldBe false
    }

    @Test
    fun `a volume press moves by its fraction`() {
        val moves = mutableListOf<Float>()

        handle(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, held = false, moves = moves)

        moves shouldBe listOf(0.5f)
    }

    /** The resume veil holds every key, since a move would cancel the landing it waits on. */
    @Test
    fun `a held page takes a volume press without moving`() {
        val moves = mutableListOf<Float>()

        val handled = handle(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, held = true, moves = moves)

        (handled to moves) shouldBe (true to emptyList<Float>())
    }

    @Test
    fun `a held page takes a reader key without acting`() {
        val asked = mutableListOf<Action>()

        val handled = handle(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_UP, held = true, perform = { asked += it })

        (handled to asked) shouldBe (true to emptyList<Action>())
    }

    private fun handle(
        code: Int,
        keyAction: Int,
        held: Boolean,
        moves: MutableList<Float> = mutableListOf(),
        perform: (Action) -> Unit = {},
    ): Boolean = NovelVolumeKeys.handleKey(
        key(code, keyAction),
        rtl = false,
        held = held,
        active = { true },
        settings = mockk {
            every { volumeButtonsInverted } returns false
            every { volumeButtonsFraction } returns 0.5f
        },
        perform = perform,
    ) { moves += it }

    private fun key(code: Int, keyAction: Int): KeyEvent = mockk {
        every { keyCode } returns code
        every { action } returns keyAction
        every { metaState } returns 0
    }
}
