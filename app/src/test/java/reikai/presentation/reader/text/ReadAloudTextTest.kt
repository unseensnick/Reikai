package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A sentence is named in a paragraph's read-aloud text, which collapses spaces the chunk still holds. */
class ReadAloudTextTest {

    private val chunk = "  First  one.\tSecond one.\n"
    private val paragraph = readAloudParagraphs(listOf(chunk)).single()

    @Test
    fun `a sentence after collapsed spaces lands on its own characters in the chunk`() {
        val second = paragraph.text.indexOf("Second")

        val (start, end) = paragraph.chunkRange(chunk, second until paragraph.text.length)!!

        chunk.substring(start, end) shouldBe "Second one."
    }

    @Test
    fun `the first sentence starts where the paragraph does`() {
        val (start, end) = paragraph.chunkRange(chunk, 0 until "First one.".length)!!

        chunk.substring(start, end) shouldBe "First  one."
    }

    @Test
    fun `a line is counted without its spaces or pictures`() {
        shownCharCount("A b\n\uFFFCcd", "A b\n\uFFFCc".length) shouldBe 3
    }

    @Test
    fun `a counted character is found again at its offset`() {
        val chunk = "A b\n\uFFFCcd"

        shownCharOffset(chunk, shownCharCount(chunk, chunk.indexOf('c'))) shouldBe chunk.indexOf('c')
    }

    @Test
    fun `a count past the text names no offset`() {
        shownCharOffset("ab", 2) shouldBe null
    }

    @Test
    fun `a range past the paragraph names nothing`() {
        paragraph.chunkRange(chunk, 0 until paragraph.text.length + 1) shouldBe null
    }
}
