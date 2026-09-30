package reikai.data.novel.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.tts.TtsPiece

/**
 * The identity check every engine callback goes through, so one from a piece a later speak flushed
 * reaches nothing. The listener wiring itself needs a real TextToSpeech, which a JVM test cannot build.
 */
class SpokenParagraphTest {

    private val pieces = listOf(TtsPiece("One.", 0, 4), TtsPiece("Two.", 5, 9))

    private fun paragraph(generation: Int) = SpokenParagraph(generation, pieces) {}

    @Test
    fun `a piece of the paragraph being spoken is found by its id`() {
        val current = paragraph(2)

        current.indexOf(current.idOf(1)) shouldBe 1
    }

    @Test
    fun `a piece of a paragraph already flushed matches nothing`() {
        val flushed = paragraph(1)

        paragraph(2).indexOf(flushed.idOf(1)) shouldBe null
    }
}
