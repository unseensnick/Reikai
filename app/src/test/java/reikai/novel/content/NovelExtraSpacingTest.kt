package reikai.novel.content

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NovelExtraSpacingTest {

    @Test
    @DisplayName("padding entities before a paragraph boundary are dropped")
    fun dropsPaddingEntities() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("<p>Real text&nbsp;&nbsp;</p>")

        cleaned shouldBe "<p>Real text</p>"
    }

    @Test
    @DisplayName("a run of three or more breaks collapses to two")
    fun collapsesBreakRuns() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("One<br><br><br><br>Two")

        cleaned shouldBe "One<br><br>Two"
    }

    @Test
    @DisplayName("breaks padding a paragraph boundary are dropped, since the paragraph already spaces it")
    fun dropsBreaksBesideParagraphs() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("<p>One</p><br><br><p>Two</p>")

        cleaned shouldNotContain "<br>"
    }

    /** A pair with a paragraph on one side only, which the lone-break pass cannot clear on its own. */
    @Test
    @DisplayName("a pair of breaks after a paragraph is dropped even where bare text follows")
    fun dropsBreakPairAfterAParagraph() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("<p>One</p><br><br>Two")

        cleaned shouldBe "<p>One</p>Two"
    }

    /** A tag may break before its attributes, and is still the paragraph the other passes count it as. */
    @Test
    @DisplayName("a break before a paragraph tag broken by a newline is dropped")
    fun dropsBreakBeforeATagBrokenByANewline() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("Hi<br>\n<p\nclass=\"x\">Next</p>")

        cleaned shouldBe "Hi<p\nclass=\"x\">Next</p>"
    }

    /** One break between paragraphs, which the pair pass cannot match. */
    @Test
    @DisplayName("a single break between paragraphs is dropped")
    fun dropsLoneBreakBetweenParagraphs() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("<p>One</p><br><p>Two</p>")

        cleaned shouldBe "<p>One</p><p>Two</p>"
    }

    @Test
    @DisplayName("a break between words is left alone, because it is the source's own line break")
    fun keepsBreaksInsideText() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("<p>One<br>Two</p>")

        cleaned shouldBe "<p>One<br>Two</p>"
    }

    /** A `pre` block's breaks are the block's own layout, not padding a source added. */
    @Test
    @DisplayName("breaks inside a pre block are left alone")
    fun keepsBreaksInsidePre() {
        val pre = "<pre>a<br>\n<br>\n<br>\nb</pre>"

        NovelHtmlUtils.removeExtraParagraphSpacing(pre) shouldBe pre
    }

    @Test
    @DisplayName("markup with nothing to strip comes back unchanged")
    fun leavesCleanMarkupAlone() {
        val clean = "<p>One</p><p>Two</p>"

        NovelHtmlUtils.removeExtraParagraphSpacing(clean) shouldBe clean
    }

    /** Sources style their paragraphs, so the tag a break pads often carries attributes. */
    @Test
    @DisplayName("breaks padding a paragraph tag with attributes are dropped")
    fun dropsBreaksAfterAParagraphWithAttributes() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("<p class=\"text\"><br><br>One</p>")

        cleaned shouldBe "<p class=\"text\">One</p>"
    }

    @Test
    @DisplayName("a single break after a paragraph tag with attributes is dropped")
    fun dropsLoneBreakAfterAParagraphWithAttributes() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("<p style=\"margin: 0\"><br>One</p>")

        cleaned shouldBe "<p style=\"margin: 0\">One</p>"
    }

    @Test
    @DisplayName("a break between a paragraph tag and its text with other tags in between is dropped")
    fun dropsBreakBehindOtherTags() {
        val cleaned = NovelHtmlUtils.removeExtraParagraphSpacing("<p><span> <br>One</span></p>")

        cleaned shouldBe "<p><span> One</span></p>"
    }

    @Test
    @DisplayName("a break after text in a styled paragraph is left alone")
    fun keepsBreaksInsideTextOfAStyledParagraph() {
        val styled = "<p class=\"text\"><span>One<br>Two</span></p>"

        NovelHtmlUtils.removeExtraParagraphSpacing(styled) shouldBe styled
    }

    /** The pass runs on the source's own markup, before any parser has rewritten a break as `<br>`. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["<br/>", "<br />", "<br class=\"x\">"])
    @DisplayName("a run of breaks collapses to two however the break is written")
    fun collapsesBreakRunsInAnySpelling(br: String) {
        NovelHtmlUtils.removeExtraParagraphSpacing("One$br$br$br${br}Two") shouldBe "One<br><br>Two"
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["<br/>", "<br />"])
    @DisplayName("breaks padding a paragraph are dropped however the break is written")
    fun dropsParagraphBreaksInAnySpelling(br: String) {
        val padded = "<p>${br}One</p>$br$br<p>Two$br</p>"

        NovelHtmlUtils.removeExtraParagraphSpacing(padded) shouldBe "<p>One</p><p>Two</p>"
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["&#160;", "&#xa0;", " "])
    @DisplayName("padding before a paragraph boundary is dropped however the no-break space is written")
    fun dropsPaddingInAnySpelling(space: String) {
        NovelHtmlUtils.removeExtraParagraphSpacing("<p>Real text$space$space</p>") shouldBe "<p>Real text</p>"
    }
}
