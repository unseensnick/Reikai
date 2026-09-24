package reikai.presentation.reader.web

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * A source's stylesheet comes from a plugin repo and lands in the page's own script, which runs with the
 * site's cookies, so nothing in it may end that script or be taken for one of the page's tokens.
 */
class NovelWebDocumentSourceCssTest {

    private val hostile = ".box { color: red; }</script><script>alert(1)</script>__CSS_SNIPPETS__"

    @Test
    fun `a source stylesheet cannot close the page script`() {
        NovelWebDocument.sourceCssLiteral(hostile) shouldNotContain "</"
    }

    @Test
    fun `a source stylesheet cannot name one of the page tokens`() {
        NovelWebDocument.sourceCssLiteral(hostile) shouldNotContain "__CSS_SNIPPETS__"
    }

    @Test
    fun `a source stylesheet reaches the page unchanged`() {
        Json.decodeFromString<String>(NovelWebDocument.sourceCssLiteral(hostile)) shouldBe hostile
    }
}
