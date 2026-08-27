package reikai.novel.source

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * An lnreader registry names a language in the language itself, so these pin that those names reach
 * the same code the manga sources use rather than each becoming a section of its own.
 */
class NovelSourceLanguageTest {

    @Test
    fun `a language named in its own language becomes its code`() {
        "Espa\u00F1ol".toLangCode() shouldBe "es"
    }

    @Test
    fun `Arabic matches despite the invisible mark the registry prefixes it with`() {
        "\u200E\u0627\u0644\u0639\u0631\u0628\u064A\u0629".toLangCode() shouldBe "ar"
    }

    @Test
    fun `multi-language plugins join the manga sources' Multi section`() {
        "Multi".toLangCode() shouldBe "all"
    }

    @Test
    fun `a language already given as a code is left alone`() {
        "es".toLangCode() shouldBe "es"
    }

    @Test
    fun `a language nobody has mapped is kept rather than dropped`() {
        "Klingon".toLangCode() shouldBe "Klingon"
    }

    @Test
    fun `an installed source reports the same code as the registry entry it came from`() {
        source("T\u00FCrk\u00E7e").langCode() shouldBe "T\u00FCrk\u00E7e".toLangCode()
    }

    private fun source(lang: String) = mockk<NovelSource> { every { this@mockk.lang } returns lang }
}
