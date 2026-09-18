package reikai.novel.content

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

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
}
