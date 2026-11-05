package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Test

class HtmlAnchorsTest {

    @Test
    fun `a plus stays a plus while an escape is decoded`() {
        decodePercentKeepingPlus("a+b%20c") shouldBe "a+b c"
    }

    @Test
    fun `a malformed escape decodes to nothing`() {
        decodePercentKeepingPlus("%zz") shouldBe null
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
