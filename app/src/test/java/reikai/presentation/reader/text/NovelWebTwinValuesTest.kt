package reikai.presentation.reader.text

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What the native renderer draws and the WebView page restates in its own stylesheet and script, held to
 * one value each so neither renderer moves alone. A CSS pixel is a dp.
 */
class NovelWebTwinValuesTest {

    // Unit tests run from the module directory, so the assets resolve relative to it.
    private val css = File("src/main/assets/novel-web/reader.css").readText()
    private val js = File("src/main/assets/novel-web/reader.js").readText()

    private fun number(text: String, pattern: String) = Regex(pattern).find(text)!!.groupValues[1].toFloat()

    @Test
    fun `a horizontal rule is as faint on the page as natively`() {
        number(css, """\nhr \{[^}]*?opacity: ([\d.]+);""") * 255 shouldBe
            (RuleSpan.RULE_ALPHA.toFloat() plusOrMinus 1f)
    }

    @Test
    fun `the read-aloud outline stands as far off the text on the page`() {
        number(js, """var OUTLINE_PAD_PX = ([\d.]+);""") shouldBe ReadAloudBoxDecoration.OUTLINE_PAD_DP
    }

    @Test
    fun `the read-aloud outline is as thick on the page`() {
        number(css, """\.rk-tts-outline \{[^}]*?border: ([\d.]+)px""") shouldBe ReadAloudBoxDecoration.OUTLINE_STROKE_DP
    }

    @Test
    fun `the read-aloud outline is as round on the page`() {
        number(css, """\.rk-tts-outline \{[^}]*?border-radius: ([\d.]+)px""") shouldBe
            ReadAloudBoxDecoration.OUTLINE_RADIUS_DP
    }

    @Test
    fun `a failure box's border is as thick on the page`() {
        number(css, """\.rk-failure \{[^}]*?border: ([\d.]+)px""") shouldBe ImageFailureDrawable.BORDER_DP
    }

    @Test
    fun `a failure box is as round on the page`() {
        number(css, """\.rk-failure \{[^}]*?border-radius: ([\d.]+)px""") shouldBe ImageFailureDrawable.CORNER_DP
    }

    @Test
    fun `a failure box is as faint on the page`() {
        number(css, """\.rk-failure \{[^}]*?opacity: ([\d.]+);""") shouldBe ImageFailureDrawable.BOX_ALPHA
    }

    @Test
    fun `a loading picture's box is as tall on the page`() {
        number(css, """img\[src]:not\(\.rk-loaded\) \{[^}]*?height: ([\d.]+)px""") shouldBe
            NovelImageGetter.PLACEHOLDER_HEIGHT_DP.toFloat()
    }

    @Test
    fun `a loading picture's box is as round on the page`() {
        number(css, """img\[src]:not\(\.rk-loaded\) \{[^}]*?border-radius: ([\d.]+)px""") shouldBe
            NovelImageGetter.PLACEHOLDER_CORNER_DP.toFloat()
    }
}
