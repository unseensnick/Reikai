package reikai.novel.content

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Auto-split breaks a wall of text into paragraphs, for a source that ships one. Ported whole from
 * tsundoku, which has no test over it either, so these are the first.
 */
class NovelTextSplitterTest {

    // Capitalised: a full stop followed by a lower-case word is no sentence end to the sentence rule.
    private fun sentences(count: Int, wordsEach: Int): String =
        (1..count).joinToString(" ") { List(wordsEach) { "Word" }.joinToString(" ") + "." }

    @Test
    fun `a word count of zero leaves the text alone`() {
        val text = sentences(count = 5, wordsEach = 30)

        NovelTextSplitter.splitText(text, wordCount = 0, isHtml = false) shouldBe text
    }

    /**
     * The break waits for the end of a sentence, so a paragraph runs past the target rather than
     * cutting a sentence in half. Twenty-word sentences against a target of twenty-five means the
     * first break can only fall at the end of the second.
     */
    @Test
    fun `a break falls at the end of a sentence, not at the word count`() {
        val text = sentences(count = 4, wordsEach = 20)

        val split = NovelTextSplitter.splitText(text, wordCount = 25, isHtml = false)

        split.split("\n\n").first().split(" ").size shouldBe 40
    }

    /** A paragraph break already follows the last sentence, so a second one would leave a gap. */
    @Test
    fun `the last sentence gets no break`() {
        val text = sentences(count = 2, wordsEach = 20)

        NovelTextSplitter.splitText(text, wordCount = 20, isHtml = false) shouldBe
            "${sentences(count = 1, wordsEach = 20)}\n\n ${sentences(count = 1, wordsEach = 20)}"
    }

    @Test
    fun `text with no sentence ending is never broken`() {
        val text = List(200) { "word" }.joinToString(" ")

        NovelTextSplitter.splitText(text, wordCount = 20, isHtml = false) shouldNotContain "\n\n"
    }

    /** A line break restarts the count, so a text file already in paragraphs is left alone. */
    @Test
    fun `plain text keeps its own paragraphs and line breaks`() {
        val text = "${sentences(count = 1, wordsEach = 15)}\n\n${sentences(count = 1, wordsEach = 15)}\n" +
            sentences(count = 1, wordsEach = 15)

        NovelTextSplitter.splitText(text, wordCount = 20, isHtml = false) shouldBe text
    }

    @Test
    fun `plain text breaks an overlong line and keeps the paragraph after it`() {
        val first = sentences(count = 1, wordsEach = 30)
        val second = sentences(count = 1, wordsEach = 10)
        val shortParagraph = sentences(count = 1, wordsEach = 5)

        val split = NovelTextSplitter.splitText("$first $second\n\n$shortParagraph", wordCount = 20, isHtml = false)

        split.split(Regex("\n\n\\s*")) shouldBe listOf(first, second, shortParagraph)
    }

    /** Below twenty the target is raised, so a small number cannot shred the text into fragments. */
    @Test
    fun `a target below the floor is raised to it`() {
        val text = sentences(count = 6, wordsEach = 5)

        val split = NovelTextSplitter.splitText(text, wordCount = 1, isHtml = false)

        split.split("\n\n").first().split(" ").size shouldBe 20
    }

    /** Breaks rather than paragraph tags, so a split stays valid inside a div-based chapter. */
    @Test
    fun `html is split with line breaks rather than blank lines`() {
        val html = "<p>${sentences(count = 4, wordsEach = 20)}</p>"

        val split = NovelTextSplitter.splitText(html, wordCount = 25, isHtml = true)

        split shouldContain "<br><br>"
        split shouldNotContain "\n\n"
    }

    /** An opening tag restarts the count, so a chapter already in paragraphs is left alone. */
    @Test
    fun `an existing paragraph shorter than the target gets no break`() {
        val html = (1..4).joinToString("") { "<p>${sentences(count = 1, wordsEach = 10)}</p>" }

        NovelTextSplitter.splitText(html, wordCount = 25, isHtml = true) shouldBe html
    }

    /** A stylesheet, a script, preformatted text or a text box holds content, not prose to break. */
    @ParameterizedTest
    @ValueSource(strings = ["pre", "script", "style", "textarea"])
    fun `a verbatim block is left as it came`(tag: String) {
        val html = "<$tag>${sentences(count = 1, wordsEach = 25)}\nline two</$tag>"

        NovelTextSplitter.splitText(html, wordCount = 20, isHtml = true) shouldBe html
    }

    /** Chinese and Japanese put no space between words, so a whitespace count saw one word per paragraph. */
    @Test
    fun `an unspaced chinese wall is split by sentence`() {
        val sentence = "我们今天去了学校。"
        val text = sentence.repeat(10)

        val split = NovelTextSplitter.splitText(text, wordCount = 20, isHtml = false)

        split.split("\n\n") shouldBe listOf(sentence.repeat(3), sentence.repeat(3), sentence.repeat(3), sentence)
    }

    @Test
    fun `a sentence ending inside a closing quote breaks after the quote`() {
        val sentence = List(20) { "Word" }.joinToString(" ") + ".\""
        val text = List(4) { sentence }.joinToString(" ")

        val split = NovelTextSplitter.splitText(text, wordCount = 25, isHtml = false)

        split.split("\n\n").first() shouldBe "$sentence $sentence"
    }

    @Test
    fun `a japanese sentence keeps its closing bracket before the break`() {
        val sentence = "「今日は学校に行きました。」"
        val text = sentence.repeat(4)

        val split = NovelTextSplitter.splitText(text, wordCount = 20, isHtml = false)

        split.split("\n\n").first() shouldBe sentence.repeat(2)
    }

    /** An entity-escaped quote is the same quote: the break goes after it, never between it and its sentence. */
    @Test
    fun `an escaped closing quote stays with its sentence`() {
        val sentence = List(20) { "Word" }.joinToString(" ") + ".&quot;"
        val html = "<p>" + List(4) { sentence }.joinToString(" ") + "</p>"

        val split = NovelTextSplitter.splitText(html, wordCount = 25, isHtml = true)

        split shouldContain "$sentence $sentence<br><br>"
    }

    /** Inline markup is not a sentence end, so bold text in the middle of a sentence cannot move the break. */
    @Test
    fun `a sentence running across inline markup breaks only after its end`() {
        val sentence = "Word word <b>bold word</b> " + List(16) { "word" }.joinToString(" ") + "."
        val html = "<p>" + List(4) { sentence }.joinToString(" ") + "</p>"

        val split = NovelTextSplitter.splitText(html, wordCount = 25, isHtml = true)

        split shouldBe "<p>$sentence $sentence<br><br> $sentence $sentence</p>"
    }

    /**
     * A page lays out a source's line break as a space. Android's sentence rule ends a sentence at a
     * newline and the JDK's only at a paragraph separator, so the separator stands in for both here.
     */
    @Test
    fun `a line break in the html source does not end a sentence`() {
        val wrapped = List(25) { "Word" }.joinToString(" ") + " " + List(5) { "word" }.joinToString(" ") + "."
        val html = "<p>$wrapped ${sentences(count = 1, wordsEach = 5)}</p>"

        NovelTextSplitter.splitText(html, wordCount = 20, isHtml = true) shouldBe
            "<p>$wrapped<br><br> ${sentences(count = 1, wordsEach = 5)}</p>"
    }

    /** The count restarts after a verbatim block, so prose before it cannot break the prose after early. */
    @Test
    fun `the count restarts after a verbatim block`() {
        val html = "${sentences(count = 1, wordsEach = 15)}<pre>code</pre>${sentences(count = 1, wordsEach = 10)}"

        NovelTextSplitter.splitText(html, wordCount = 20, isHtml = true) shouldBe html
    }
}
