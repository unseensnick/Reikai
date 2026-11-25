package reikai.domain.novel.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.Locale

/**
 * Android's sentence rule is ICU's, which these unit tests cannot run, so the segments go in as ICU cuts
 * them (measured with Node's ICU sentence segmenter) and come out as both platforms should read them.
 */
class TextSegmentsTest {

    @ParameterizedTest(name = "{0}{1}")
    @MethodSource("pairs")
    fun `an opener that starts the next sentence goes with it`(first: String, second: String, icuTakes: Int) {
        val text = first + second
        val icu = listOf(0 to first.length + icuTakes, first.length + icuTakes to text.length)

        TextSegments.withOpenersMovedOn(text, icu) shouldBe listOf(0 to first.length, first.length to text.length)
    }

    @Test
    fun `the last sentence keeps what it ends with`() {
        TextSegments.withOpenersMovedOn("Go.「", listOf(0 to 4)) shouldBe listOf(0 to 4)
    }

    /** A first sentence made only of openers keeps its first character rather than walking off the text. */
    @Test
    fun `an opener-only first sentence keeps one character`() {
        TextSegments.withOpenersMovedOn("「「a", listOf(0 to 2, 2 to 3)) shouldBe listOf(0 to 1, 1 to 3)
    }

    /** A paragraph cut from its middle, as the splitter cuts an oversized sentence, keeps the offsets of the whole. */
    @Test
    fun `segments of a range are offsets in the whole text`() {
        TextSegments.sentences("Skip. One. Two.", from = 6, to = 15, locale = Locale.ENGLISH) shouldBe
            listOf(6 to 11, 11 to 15)
    }

    companion object {
        @JvmStatic
        fun pairs() = listOf(
            Arguments.of("「今日は学校に行きました。」", "「明日も行きます。」", 1),
            Arguments.of("“你去哪儿？”", "“学校。”", 1),
            // An opening quote after a closing bracket moves on as after a closing quote.
            Arguments.of("（好）", "“走。”", 1),
            // German opens with „ and closes with “, so only the „ moves on.
            Arguments.of("Er sagte: „Geh.“", "„Nein.“", 1),
            // A space already ends the first sentence before the bracket.
            Arguments.of("Go now. ", "(Next one.", 0),
        )
    }
}
