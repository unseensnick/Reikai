package eu.kanade.tachiyomi.ui.updates

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.preference.TriState

/** Both updates feeds hand their chapter filters to SQL through this one conversion. */
class TriStateToBooleanTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("cases")
    fun `a filter state becomes the query's nullable flag`(state: TriState, expected: Boolean?) {
        state.toBooleanOrNull() shouldBe expected
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Arguments.of(TriState.DISABLED, null),
            Arguments.of(TriState.ENABLED_IS, true),
            Arguments.of(TriState.ENABLED_NOT, false),
        )
    }
}
