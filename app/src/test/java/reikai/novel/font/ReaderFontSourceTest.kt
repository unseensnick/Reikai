package reikai.novel.font

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.reader.builtInReaderFonts
import java.io.File

/** Where each kind of stored family is drawn from, which both renderers and the fonts screen follow. */
class ReaderFontSourceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    fun `a stored family resolves to one kind of source`(family: String, expected: ReaderFontSource) {
        readerFontSource(family) shouldBe expected
    }

    @Test
    fun `every built-in font is a generic family or a bundled file that exists`() {
        val missing = builtInReaderFonts.map { readerFontSource(it.family) }
            .filterIsInstance<ReaderFontSource.Bundled>()
            .filterNot { File("src/main/assets", it.assetPath).isFile }
        missing shouldBe emptyList()
    }

    @Test
    fun `no built-in font falls through to a user file`() {
        builtInReaderFonts.map { readerFontSource(it.family) }
            .filterIsInstance<ReaderFontSource.UserFile>() shouldBe emptyList()
    }

    companion object {
        @JvmStatic
        fun sources() = listOf(
            Arguments.of("", ReaderFontSource.SourceDefault),
            Arguments.of(" ", ReaderFontSource.SourceDefault),
            Arguments.of("serif", ReaderFontSource.Generic(GenericFontFamily.SERIF)),
            Arguments.of("sans-serif", ReaderFontSource.Generic(GenericFontFamily.SANS_SERIF)),
            Arguments.of("monospace", ReaderFontSource.Generic(GenericFontFamily.MONOSPACE)),
            Arguments.of("My_Font.ttf", ReaderFontSource.UserFile("My_Font.ttf")),
            Arguments.of("x.OTF", ReaderFontSource.UserFile("x.OTF")),
            Arguments.of("lora", ReaderFontSource.Bundled("fonts/lora.ttf")),
        )
    }
}
