package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Read aloud with no reader open counts a chapter's paragraphs by [chapterParagraphs], and the native
 * renderer counts them over the text it shows; a position saved by one must name the same text in the
 * other, so each fixture's two lists are equal.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ChapterParagraphsTest(private val name: String, private val html: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun fixtures() = listOf(
            arrayOf("plain", "<p>One  two.</p><p>Three\tfour.</p>"),
            arrayOf("ruby", "<p><ruby>漢<rt>かん</rt>字<rp>(</rp><rt>じ</rt><rp>)</rp></ruby>を読む</p>"),
            arrayOf("rule lines", "<p>Before</p><p>&nbsp;</p><hr><p>&nbsp;&nbsp;</p><p>After</p>"),
            arrayOf("pictures", "<p>Text <img src=\"a.png\"> more</p><img src=\"b.png\"><p>End</p>"),
            arrayOf("table", "<table><tr><td>A</td><td>B</td></tr><tr><th>C</th><td>D</td></tr></table>"),
            arrayOf(
                "lists",
                "<ul><li>First</li><li>Second</li></ul><ol><li>Third</li></ol><dl><dt>T</dt><dd>D</dd></dl>",
            ),
            arrayOf("breaks", "<p>Line<br><br><br>gap</p><pre>a\nb</pre>"),
            arrayOf("links", "<p><a href=\"/x\">Link</a> and <a href=\"#n\">note</a></p>"),
        )
    }

    @Test
    fun `the paragraphs with no reader open are the native renderer's`() {
        chapterParagraphs(html, BASE) shouldBe shownParagraphs()
    }

    /** What the native renderer's surface answers, over the text it styles and splits into chunks. */
    private fun shownParagraphs(): List<String> {
        val styled = NovelTextRenderer.styleForScreen(
            text = chapterSpanned(html, BASE, displayWidth = 1080, imageGetter = null),
            context = RuntimeEnvironment.getApplication(),
            emPx = 40,
            spacingPx = 20,
            contentWidth = 1000,
            baseUrl = BASE,
            bionic = true,
            onAnchor = { _, _ -> },
        )
        val chunks = NovelTextRenderer.chunkRanges(styled).map { (start, end) -> styled.subSequence(start, end) }
        return readAloudParagraphs(chunks).map { it.text }
    }
}

private const val BASE = "https://example.org/novel/1"
