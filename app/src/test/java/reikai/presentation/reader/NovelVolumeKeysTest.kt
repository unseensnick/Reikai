package reikai.presentation.reader

import android.view.KeyEvent
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.Action
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NovelVolumeKeysTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "a fraction below the minimum moves a tenth of the screen | DOWN | false | 0.05 | 0.1",
            "a fraction above a screen moves one screen | DOWN | false | 1.5 | 1.0",
            "volume down reads on | DOWN | false | 0.5 | 0.5",
            "volume up reads back | UP | false | 0.5 | -0.5",
            "inverted volume down reads back | DOWN | true | 0.5 | -0.5",
            "inverted volume up reads on | UP | true | 0.5 | 0.5",
        ],
    )
    fun `a volume press scrolls a signed share of the screen`(
        @Suppress("UNUSED_PARAMETER") name: String,
        key: String,
        inverted: Boolean,
        fraction: Float,
        expected: Float,
    ) {
        val code = if (key == "DOWN") KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP
        NovelVolumeKeys.scrollFraction(code, inverted, fraction) shouldBe expected
    }

    /** Both readers ask this, so the volume stays the system's with the setting off or the menu up. */
    @ParameterizedTest(name = "enabled {0}, menu up {1}")
    @CsvSource("true, false, true", "true, true, false", "false, false, false")
    fun `the volume keys are the reader's only while enabled and the menu is down`(
        enabled: Boolean,
        menuVisible: Boolean,
        expected: Boolean,
    ) {
        volumeKeysActive(enabled, menuVisible) shouldBe expected
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

    @Test
    fun `an inactive reader gives the volume key back to the system`() {
        val moves = mutableListOf<Float>()

        val handled = handle(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, held = false, moves, active = false)

        handled shouldBe false
        moves shouldBe emptyList()
    }

    /** Consumed on release too, or the system volume UI would show after every press. */
    @Test
    fun `a volume release is taken without moving`() {
        val moves = mutableListOf<Float>()

        val handled = handle(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, held = false, moves = moves)

        handled shouldBe true
        moves shouldBe emptyList()
    }

    /** The resume veil holds every key, since a move would cancel the landing it waits on. */
    @Test
    fun `a held page takes a volume press without moving`() {
        val moves = mutableListOf<Float>()

        val handled = handle(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, held = true, moves = moves)

        handled shouldBe true
        moves shouldBe emptyList()
    }

    @Test
    fun `a held page takes a reader key without acting`() {
        val asked = mutableListOf<Action>()

        val handled = handle(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_UP, held = true, perform = { asked += it })

        handled shouldBe true
        asked shouldBe emptyList()
    }

    private fun handle(
        code: Int,
        keyAction: Int,
        held: Boolean,
        moves: MutableList<Float> = mutableListOf(),
        active: Boolean = true,
        perform: (Action) -> Unit = {},
    ): Boolean = NovelVolumeKeys.handleKey(
        key(code, keyAction),
        rtl = false,
        held = held,
        active = { active },
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
