package reikai.presentation.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class HopperLongPressActionTest {

    /** The stored `hopper_long_press` values every install and backup already holds. */
    @Test
    fun `each action keeps the code it has always been stored under`() {
        HopperLongPressAction.entries.associate { it.code to it.name } shouldBe mapOf(
            0 to "SEARCH",
            1 to "EXPAND_COLLAPSE",
            2 to "DISPLAY",
            3 to "GROUP",
            4 to "RANDOM",
            5 to "RANDOM_GLOBAL",
        )
    }

    @ParameterizedTest
    @EnumSource(HopperLongPressAction::class)
    fun `a stored code reads back as its action`(action: HopperLongPressAction) {
        HopperLongPressAction.fromCode(action.code) shouldBe action
    }

    @Test
    fun `a code no action holds reads as no action`() {
        HopperLongPressAction.fromCode(6) shouldBe null
    }
}
