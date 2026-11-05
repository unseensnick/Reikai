package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class EntryLanguageTest {

    @ParameterizedTest(name = "{0} reads as {1}")
    @CsvSource(
        "en, en",
        "en-US, en",
        "EN_gb, en",
        "ja, ja",
        "pt-BR, pt-BR",
        "pt-PT, pt",
        "es-419, es-419",
        "es-ES, es",
        "zh-Hans, zh-Hans",
        "zh-CN, zh-Hans",
        "zh-Hant, zh-Hant",
        "zh-TW, zh-Hant",
        "zh, zh",
    )
    fun `a book's language tag reads as the code sources use`(tag: String, code: String) {
        localLanguage(tag) shouldBe code
    }

    @ParameterizedTest(name = "\"{0}\" names no language")
    @CsvSource("''", "'  '", "und", "mul")
    fun `a blank or undetermined tag names no language`(tag: String) {
        localLanguage(tag) shouldBe null
    }

    @Test
    fun `a missing tag names no language`() {
        localLanguage(null) shouldBe null
    }

    @Test
    fun `a local entry shows its own language`() {
        entryLanguage(isLocal = true, own = "ja", source = "other") shouldBe "ja"
    }

    @Test
    fun `a local entry with no language of its own shows none`() {
        entryLanguage(isLocal = true, own = null, source = "other") shouldBe ""
    }

    @Test
    fun `an online entry shows its source's language`() {
        entryLanguage(isLocal = false, own = "ja", source = "en") shouldBe "en"
    }
}
