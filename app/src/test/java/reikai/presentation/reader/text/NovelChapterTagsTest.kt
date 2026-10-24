package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class NovelChapterTagsTest {

    /** A source's site as a plugin names it, which is the base the reader hands a chapter. */
    private val baseUrl = "https://site.example"

    private fun hrefsAfterPrepare(html: String): List<String> {
        val doc = Jsoup.parse(html)
        NovelChapterTags.prepare(doc, baseUrl)
        return doc.select("a").map { it.attr("href") }
    }

    /** jsoup refuses an empty id, and the throw used to cost the chapter its whole cleanup pass. */
    @Test
    fun `a bare hash link does not stop the anchors after it`() {
        hrefsAfterPrepare("<a href=\"#\">top</a><a href=\"#n\">note</a><p id=\"n\">text</p>")[1] shouldBe
            "${NovelChapterTags.ANCHOR_HREF}0"
    }

    @Test
    fun `a link naming its target with an escape finds it`() {
        hrefsAfterPrepare("<a href=\"#note%20one\">note</a><p id=\"note one\">text</p>").single() shouldBe
            "${NovelChapterTags.ANCHOR_HREF}0"
    }

    /** Not a valid escape, so only the name as written can match. */
    @Test
    fun `a link whose name holds a percent sign finds its target`() {
        hrefsAfterPrepare("<a href=\"#100%\">note</a><p id=\"100%\">text</p>").single() shouldBe
            "${NovelChapterTags.ANCHOR_HREF}0"
    }

    /** The document's base is the source's site, which a link can name with a fragment; the page jumps
     *  within itself for one, and so must the text. */
    @Test
    fun `a link to the document's base with a fragment jumps within it`() {
        hrefsAfterPrepare("<a href=\"https://site.example/#n\">note</a><p id=\"n\">text</p>").single() shouldBe
            "${NovelChapterTags.ANCHOR_HREF}0"
    }

    @Test
    fun `a link to another chapter's fragment stays a link`() {
        hrefsAfterPrepare("<a href=\"chapter-6.html#n\">next</a><p id=\"n\">text</p>").single() shouldBe
            "chapter-6.html#n"
    }
}
