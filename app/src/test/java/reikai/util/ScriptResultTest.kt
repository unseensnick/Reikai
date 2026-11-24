package reikai.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** What a script's result, as `evaluateJavascript` encodes it, reads back as. */
class ScriptResultTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a script result reads back as the string the script returned`(result: String?, expected: String?) {
        scriptString(result) shouldBe expected
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Arguments.of(""""<p class=\"a\">b\nc</p>"""", "<p class=\"a\">b\nc</p>"),
            Arguments.of("null", null),
            Arguments.of("{}", null),
            Arguments.of(null, null),
        )
    }
}
