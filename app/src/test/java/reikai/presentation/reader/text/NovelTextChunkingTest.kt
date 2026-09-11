package reikai.presentation.reader.text

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class NovelTextChunkingTest {

    /** Forty paragraphs of 400 characters, long enough to split into several chunks. */
    private val chapter = (1..40).joinToString("") { "p$it ".padEnd(400, 'x') + "\n" }

    private fun chunks(text: String) = NovelTextRenderer.chunkRanges(text).map { (start, end) ->
        text.substring(start, end)
    }

    @Test
    @DisplayName("a chapter splits into more than one chunk, or the cases below prove nothing")
    fun aLongChapterSplits() {
        chunks(chapter).size shouldBeGreaterThan 1
    }

    @Test
    @DisplayName(
        "each chunk drops the newline it ends on and nothing else, since a view ending in one draws an empty line",
    )
    fun chunksDropOnlyTheirClosingNewline() {
        chunks(chapter).joinToString("\n") shouldBe chapter.dropLast(1)
    }

    @Test
    @DisplayName("text that does not end in a newline loses nothing at its end")
    fun textWithoutAClosingNewlineKeepsItsLastCharacter() {
        chunks(chapter.dropLast(1)).joinToString("\n") shouldBe chapter.dropLast(1)
    }
}
