package reikai.novel.content

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NovelBareParagraphsTest {

    @Test
    fun `a chapter with no paragraphs gets one per blank line`() {
        NovelHtmlUtils.wrapBareParagraphs("One\n\nTwo") shouldBe "<p>One</p><p>Two</p>"
    }

    @Test
    fun `a blank line inside a style block stays part of the stylesheet`() {
        NovelHtmlUtils.wrapBareParagraphs("A\n\n<style>.a{}\n\n.b{}</style>") shouldContain
            "<style>.a{}\n\n.b{}</style>"
    }

    @Test
    fun `a blank line inside a script stays part of the script`() {
        NovelHtmlUtils.wrapBareParagraphs("A\n\n<script>var a=1;\n\nvar b=2;</script>") shouldContain
            "<script>var a=1;\n\nvar b=2;</script>"
    }

    @Test
    fun `a blank line inside preformatted text is kept, not split into paragraphs`() {
        NovelHtmlUtils.wrapBareParagraphs("A\n\n<PRE>x\n\ny</PRE>") shouldContain "<PRE>x\n\ny</PRE>"
    }

    @Test
    fun `a paragraph's leading space inside a script string is left alone`() {
        val chapter = "<p>A</p><script>s = '<p>&nbsp;x'</script>"

        NovelHtmlUtils.wrapBareParagraphs(chapter) shouldBe chapter
    }

    @Test
    fun `a paragraph's hand-typed indent is dropped when the tag carries attributes`() {
        NovelHtmlUtils.wrapBareParagraphs("<p class=\"t\">&nbsp;&nbsp;Hi</p>") shouldBe "<p class=\"t\">Hi</p>"
    }

    @Test
    fun `a paragraph's hand-typed indent is dropped whatever the tag's case`() {
        NovelHtmlUtils.wrapBareParagraphs("<P>&nbsp;Hi</P>") shouldBe "<P>Hi</P>"
    }

    /** The source's own markup, which no parser has yet rewritten to one spelling. */
    @ParameterizedTest
    @ValueSource(strings = ["&#xa0;", " "])
    fun `a paragraph's hand-typed indent is dropped in every no-break space spelling`(space: String) {
        NovelHtmlUtils.wrapBareParagraphs("<p>${space}Hi</p>") shouldBe "<p>Hi</p>"
    }
}
