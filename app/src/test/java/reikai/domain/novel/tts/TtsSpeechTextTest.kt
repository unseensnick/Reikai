package reikai.domain.novel.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale

class TtsSpeechTextTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "【】", "〔〕", "〖〗", "「」", "『』", "《》", "〈〉", "（）", "［］", "｛｝", "«»", "‹›", "⟨⟩", "⟦⟧",
            "()", "[]", "{}", "<>",
        ],
    )
    fun `a bracket between words is a pause`(pair: String) {
        TtsSpeechText.of("a ${pair[0]}b${pair[1]} c") shouldBe "a ,b, c"
    }

    @ParameterizedTest
    @ValueSource(strings = ["【", "」", "(", "]", "«"])
    fun `a bracket beside punctuation is a space`(bracket: String) {
        TtsSpeechText.of("a.${bracket}b") shouldBe "a. b"
    }

    @Test
    fun `a bracket before punctuation is a space`() {
        TtsSpeechText.of("a】. b") shouldBe "a . b"
    }

    @Test
    fun `a bracket opening the paragraph is a space`() {
        TtsSpeechText.of("【Skill】 ready") shouldBe " Skill, ready"
    }

    @Test
    fun `brackets side by side pause once`() {
        TtsSpeechText.of("a】【b") shouldBe "a, b"
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"", "“", "”", "„", "‟", "'", "‘", "’", "‚", "‛", "＂", "＇", "〝", "〞", "〟"])
    fun `a quote mark outside a word is a space`(quote: String) {
        TtsSpeechText.of("said ${quote}go$quote now") shouldBe "said  go  now"
    }

    @ParameterizedTest
    @ValueSource(strings = ["don't", "it’s", "l'homme"])
    fun `an apostrophe inside a word is kept`(word: String) {
        TtsSpeechText.of(word) shouldBe word
    }

    @Test
    fun `the words inside are kept`() {
        TtsSpeechText.of("「Hello there,」 she said.") shouldBe " Hello there,  she said."
    }

    @Test
    fun `every character keeps its offset`() {
        val shown = "He said 「“don't”」 (twice). 【Skill: Fire】"
        TtsSpeechText.of(shown).length shouldBe shown.length
    }

    @Test
    fun `a piece's text is the speech text at its offsets`() {
        val shown = "「First one.」 He said (twice)."
        val piece = TtsUtteranceSplitter.pieces(shown, maxLength = 100, Locale.ENGLISH, bySentence = true).last()
        piece.text shouldBe TtsSpeechText.of(shown).substring(piece.start, piece.end)
    }

    /** A closing quote after a full stop is where the sentence ends in the text as shown, which
     *  splitting the speech text instead would lose. */
    @Test
    fun `sentences are split on the text as shown`() {
        val shown = "「First one.」 Second one."
        TtsUtteranceSplitter.pieces(shown, maxLength = 100, Locale.ENGLISH, bySentence = true)
            .map { it.start until it.end } shouldBe listOf(0 until 12, 13 until shown.length)
    }
}
