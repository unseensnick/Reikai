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
    fun `a range past the paragraph names nothing`() {
        paragraph.chunkRange(chunk, 0 until paragraph.text.length + 1) shouldBe null
    }
}
