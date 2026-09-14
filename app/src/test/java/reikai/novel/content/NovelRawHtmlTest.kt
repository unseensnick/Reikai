package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A chapter shown as the markup it carries, which both readers draw as text rather than as a page. */
class NovelRawHtmlTest {

    @Test
    fun `the tags show as text`() {
        NovelHtmlUtils.htmlAsText("<p>Hi</p>") shouldBe
            "<div data-reikai-plain-text=\"1\"><p>&lt;p&gt;Hi&lt;/p&gt;</p></div>"
    }

    /** Sources send a chapter as one line, which would show as a single unreadable paragraph. */
    @Test
    fun `adjacent tags start a new line each`() {
        NovelHtmlUtils.htmlAsText("<p>A</p><p>B</p>") shouldBe
            "<div data-reikai-plain-text=\"1\"><p>&lt;p&gt;A&lt;/p&gt;</p><p>&lt;p&gt;B&lt;/p&gt;</p></div>"
    }

    /** An entity is part of the markup being shown, so it reads as the entity, not the character. */
    @Test
    fun `an entity shows as written`() {
        NovelHtmlUtils.htmlAsText("<p>&lt;D&gt;</p>") shouldBe
            "<div data-reikai-plain-text=\"1\"><p>&lt;p&gt;&amp;lt;D&amp;gt;&lt;/p&gt;</p></div>"
    }
}
