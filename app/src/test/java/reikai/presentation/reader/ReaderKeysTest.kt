package reikai.presentation.reader

import android.view.KeyEvent
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.Action
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ReaderKeysTest {

    @ParameterizedTest(name = "{0} ctrl={1} rtl={2} -> {3}")
    @MethodSource("keys")
    fun `a reader key asks for its action on release`(code: Int, ctrl: Boolean, rtl: Boolean, expected: Action) {
        val asked = mutableListOf<Action>()

        handleReaderKey(key(code, KeyEvent.ACTION_UP, if (ctrl) KeyEvent.META_CTRL_ON else 0), rtl, held = false) {
            asked += it
        }

        asked shouldBe listOf(expected)
    }

    @Test
    fun `a key press is taken without acting`() {
        val asked = mutableListOf<Action>()

        val handled =
            handleReaderKey(key(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_DOWN), rtl = false, held = false) {
                asked += it
            }

        (handled to asked) shouldBe (true to emptyList<Action>())
    }

    /** Held while a saved place waits on its pictures, as a touch is, since a move would cancel the landing. */
    @Test
    fun `a key released while the page is held is taken without acting`() {
        val asked = mutableListOf<Action>()

        val handled =
            handleReaderKey(key(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_UP), rtl = false, held = true) {
                asked += it
            }

        (handled to asked) shouldBe (true to emptyList<Action>())
    }

    @Test
    fun `a key the reader has no use for is left to the system`() {
        handleReaderKey(key(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP), rtl = false, held = false) {} shouldBe false
    }

    companion object {
        @JvmStatic
        fun keys() = listOf(
            Arguments.of(KeyEvent.KEYCODE_DPAD_RIGHT, false, false, Action.FORWARD),
            Arguments.of(KeyEvent.KEYCODE_DPAD_RIGHT, false, true, Action.BACKWARD),
            Arguments.of(KeyEvent.KEYCODE_DPAD_RIGHT, true, true, Action.FORWARD),
            Arguments.of(KeyEvent.KEYCODE_DPAD_LEFT, false, false, Action.BACKWARD),
            Arguments.of(KeyEvent.KEYCODE_DPAD_LEFT, false, true, Action.FORWARD),
            Arguments.of(KeyEvent.KEYCODE_DPAD_LEFT, true, true, Action.BACKWARD),
            Arguments.of(KeyEvent.KEYCODE_DPAD_DOWN, false, true, Action.FORWARD),
            Arguments.of(KeyEvent.KEYCODE_PAGE_DOWN, false, false, Action.FORWARD),
            Arguments.of(KeyEvent.KEYCODE_DPAD_UP, false, true, Action.BACKWARD),
            Arguments.of(KeyEvent.KEYCODE_PAGE_UP, false, false, Action.BACKWARD),
            Arguments.of(KeyEvent.KEYCODE_MENU, false, false, Action.MENU),
        )
    }
}

private fun key(code: Int, keyAction: Int, meta: Int = 0): KeyEvent = mockk {
    every { keyCode } returns code
    every { action } returns keyAction
    every { metaState } returns meta
}
