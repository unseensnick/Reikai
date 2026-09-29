package exh.assets

import exh.source.EIGHTMUSES_SOURCE_ID
import exh.source.NHENTAI_NET_SOURCE_ID
import exh.source.PURURIN_SOURCE_ID
import exh.source.eHentaiSourceIds
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Browse's source icon and the library's cover badge both pick a built-in source's logo here. */
class BuiltInSourceLogoTest {

    @ParameterizedTest
    @MethodSource("eHentaiIds")
    fun `every E-Hentai language source draws the E-Hentai logo`(sourceId: Long) {
        builtInSourceLogo(sourceId) shouldBe BuiltInSourceLogo.EHENTAI
    }

    @Test
    fun `the built-in Pururin source draws its logo`() {
        builtInSourceLogo(PURURIN_SOURCE_ID) shouldBe BuiltInSourceLogo.PURURIN
    }

    @Test
    fun `the built-in nhentai source draws its logo`() {
        builtInSourceLogo(NHENTAI_NET_SOURCE_ID) shouldBe BuiltInSourceLogo.NHENTAI
    }

    @Test
    fun `a source with an extension of its own has no built-in logo`() {
        builtInSourceLogo(EIGHTMUSES_SOURCE_ID) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tiles")
    fun `a logo without its own backdrop sits on a tile`(logo: BuiltInSourceLogo, isTiled: Boolean) {
        logo.isTiled shouldBe isTiled
    }

    companion object {
        @JvmStatic
        fun eHentaiIds() = eHentaiSourceIds.toList()

        @JvmStatic
        fun tiles() = listOf(
            Arguments.of(BuiltInSourceLogo.EHENTAI, true),
            Arguments.of(BuiltInSourceLogo.PURURIN, true),
            Arguments.of(BuiltInSourceLogo.NHENTAI, false),
        )
    }
}
