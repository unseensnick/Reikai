package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NovelChapterTitleStripTest {

    @Test
    @DisplayName("the chapter's own heading is removed")
    fun removesTheHeading() {
        val stripped = NovelHtmlUtils.stripChapterTitle("<h1>Chapter 5</h1><p>Text.</p>", "Chapter 5")

        stripped shouldBe "<p>Text.</p>"
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["script", "style", "textarea"])
    @DisplayName("a heading inside a verbatim block is left alone and the chapter's heading is removed")
    fun skipsVerbatimBlocks(block: String) {
        val verbatim = "<$block>x = \"<h2>Sponsored</h2>\"</$block>"

        val stripped = NovelHtmlUtils.stripChapterTitle("$verbatim<h1>Chapter 5</h1><p>Text.</p>", "Chapter 5")

        stripped shouldBe "$verbatim<p>Text.</p>"
    }

    @Test
    @DisplayName("a heading inside pre is a real heading, so it is still removed")
    fun preHeadingIsStillAHeading() {
        val stripped = NovelHtmlUtils.stripChapterTitle("<pre><h1>Chapter 5</h1>Text.</pre>", "Chapter 5")

        stripped shouldBe "<pre>Text.</pre>"
    }

    @Test
    @DisplayName("a first line inside a verbatim block is not taken for the title")
    fun firstLineSkipsVerbatimBlocks() {
        val content = "<script>Chapter 5</script>\nIt was raining."

        NovelHtmlUtils.stripChapterTitle(content, "Chapter 5") shouldBe content
    }
}
