package reikai.presentation.reader.text

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * What the native renderer draws and the WebView page restates in its own stylesheet and script, held to
 * one value each so neither renderer moves alone. A CSS pixel is a dp.
 */
class NovelWebTwinValuesTest {

    @Test
    fun `a horizontal rule is as faint on the page as natively`() {
        number(CSS, """\nhr \{[^}]*?opacity: ([\d.]+);""") * 255 shouldBe
            (RuleSpan.RULE_ALPHA.toFloat() plusOrMinus 1f)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("twins")
    fun `the page restates each native value`(name: String, file: String, pattern: String, native: Float) {
        number(file, pattern) shouldBe native
    }

    companion object {
        // Unit tests run from the module directory, so the assets resolve relative to it.
        private const val CSS = "src/main/assets/novel-web/reader.css"
        private const val JS = "src/main/assets/novel-web/reader.js"

        private fun number(file: String, pattern: String) =
            Regex(pattern).find(File(file).readText())!!.groupValues[1].toFloat()

        @JvmStatic
        fun twins() = listOf(
            Arguments.of(
                "the read-aloud outline stands as far off the text on the page",
                JS,
                """var OUTLINE_PAD_PX = ([\d.]+);""",
                ReadAloudBoxDecoration.OUTLINE_PAD_DP,
            ),
            Arguments.of(
                "the read-aloud outline is as thick on the page",
                CSS,
                """\.rk-tts-outline \{[^}]*?border: ([\d.]+)px""",
                ReadAloudBoxDecoration.OUTLINE_STROKE_DP,
            ),
            Arguments.of(
                "the read-aloud outline is as round on the page",
                CSS,
                """\.rk-tts-outline \{[^}]*?border-radius: ([\d.]+)px""",
                ReadAloudBoxDecoration.OUTLINE_RADIUS_DP,
            ),
            Arguments.of(
                "a failure box's border is as thick on the page",
                CSS,
                """\.rk-failure \{[^}]*?border: ([\d.]+)px""",
                ImageFailureDrawable.BORDER_DP,
            ),
            Arguments.of(
                "a failure box is as round on the page",
                CSS,
                """\.rk-failure \{[^}]*?border-radius: ([\d.]+)px""",
                ImageFailureDrawable.CORNER_DP,
            ),
            Arguments.of(
                "a failure box is as faint on the page",
                CSS,
                """\.rk-failure \{[^}]*?opacity: ([\d.]+);""",
                ImageFailureDrawable.BOX_ALPHA,
            ),
            Arguments.of(
                "a loading picture's box is as tall on the page",
                CSS,
                """img\[src]:not\(\.rk-loaded\) \{[^}]*?height: ([\d.]+)px""",
                NovelImageGetter.PLACEHOLDER_HEIGHT_DP.toFloat(),
            ),
            Arguments.of(
                "a loading picture's box is as round on the page",
                CSS,
                """img\[src]:not\(\.rk-loaded\) \{[^}]*?border-radius: ([\d.]+)px""",
                NovelImageGetter.PLACEHOLDER_CORNER_DP.toFloat(),
            ),
        )
    }
}
