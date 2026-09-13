package reikai.domain.novel.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TtsVoiceLanguagesTest {

    private val usEnglish = TtsVoice("en-us-1", "English (United States)", "en-US")
    private val ukEnglish = TtsVoice("en-gb-1", "English (United Kingdom)", "en-GB")
    private val japanese = TtsVoice("ja-jp-1", "Japanese (Japan)", "ja-JP")
    private val unknown = TtsVoice("und-1", "Unknown", "")
    private val voices = listOf(usEnglish, japanese, ukEnglish, unknown)

    @Test
    fun `no selected language shows every voice`() {
        voices.inLanguages(emptySet()) shouldBe voices
    }

    @Test
    fun `a base language matches every region of it`() {
        voices.inLanguages(setOf("en")) shouldBe listOf(usEnglish, ukEnglish)
    }

    @Test
    fun `the language list folds regions and drops a voice with no language`() {
        voices.baseLanguages() shouldBe listOf("en", "ja")
    }
}
