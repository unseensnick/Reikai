package reikai.novel.source

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The lnreader registry declares a plugin's language both ways, so these pin that the two forms
 * end up as one language rather than two sections that read the same.
 */
class NovelSourceLanguageTest {

    @Test
    fun `a language named in English becomes its code`() {
        source("Spanish").langCode() shouldBe "es"
    }

    @Test
    fun `a language already given as a code is left alone`() {
        source("es").langCode() shouldBe "es"
    }

    @Test
    fun `the two forms of one language agree`() {
        source("Portuguese").langCode() shouldBe source("pt").langCode()
    }

    @Test
    fun `a language nobody has mapped is kept rather than dropped`() {
        source("Klingon").langCode() shouldBe "Klingon"
    }

    private fun source(lang: String) = mockk<NovelSource> { every { this@mockk.lang } returns lang }
}
