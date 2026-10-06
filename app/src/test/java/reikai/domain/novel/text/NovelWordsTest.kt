package reikai.domain.novel.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NovelWordsTest {

    /** Chinese and Japanese put no space between words, so each character counts as one. */
    @ParameterizedTest(name = "{0} is {1} words")
    @CsvSource(
        "我们今天去学校。, 7",
        "one two three, 3",
        "Hello 世界, 3",
        "今日は学校に行きました。, 11",
        "'\"Well - fine,\" she said.', 4",
        "don't stop, 2",
    )
    fun `counts words the way a reader would`(text: String, words: Int) {
        NovelWords.count(text) shouldBe words
    }

    /** The word-count dialog's figure: whatever runs between spaces, as Tsundoku counts it. */
    @ParameterizedTest(name = "{0} is {1} spaced words")
    @CsvSource(
        "one two three, 3",
        "我们今天去学校。, 1",
        "'\"Well - fine,\" she said.', 5",
        "'  ', 0",
        "'one two', 2",
    )
    fun `counts what runs between spaces`(text: String, words: Int) {
        NovelWords.countSpaced(text) shouldBe words
    }
}
