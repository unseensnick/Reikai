package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HtmlAnchorsTest {

    @Test
    fun `a plus stays a plus while an escape is decoded`() {
        decodePercentKeepingPlus("a+b%20c") shouldBe "a+b c"
    }

    /** decodeURIComponent takes two ASCII hex digits only, so a sign or another script's digits name nothing. */
    @ParameterizedTest
    @ValueSource(strings = ["%zz", "%+1", "%١١", "%１１"])
    fun `a malformed escape decodes to nothing`(value: String) {
        decodePercentKeepingPlus(value) shouldBe null
    }

    /** The WebView page decodes with decodeURIComponent, which refuses this, so both readers name nothing by it. */
    @Test
    fun `an escape that is not UTF-8 decodes to nothing`() {
        decodePercentKeepingPlus("%FF") shouldBe null
    }

    @Test
    fun `a multi-byte escape decodes beside text already decoded`() {
        decodePercentKeepingPlus("%E2%98%95 é") shouldBe "☕ é"
    }

    @Test
    fun `an element named only by xml id is found`() {
        val doc = Jsoup.parse("""<body><p>a</p><p xml:id="x">b</p></body>""", "", Parser.xmlParser())

        elementNamed(doc, "x")?.text() shouldBe "b"
    }

    @Test
    fun `an old-style name is found`() {
        elementNamed(Jsoup.parse("""<p>a</p><a name="n">b</a>"""), "n")?.text() shouldBe "b"
    }
}
