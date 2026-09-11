package reikai.presentation.novel.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.presentation.reader.readerDarkPreset

/**
 * A restored backup writes these preference keys, and they land inside the reader document's
 * `<style>`, so the shapes below are what stands between a shared backup file and a script in a page
 * that holds the app's cookies.
 */
class NovelReaderCssValuesTest {

    @Test
    fun `a preset colour is passed through`() {
        cssBackgroundColor("#292832") shouldBe "#292832"
    }

    /** The Black preset's text colour, which is where the eight-digit form comes from. */
    @Test
    fun `a colour with an alpha channel is passed through`() {
        cssTextColor("#FFFFFFB3") shouldBe "#FFFFFFB3"
    }

    @Test
    fun `a colour that closes the style block falls back to the default`() {
        cssBackgroundColor("#000; } </style><script>alert(1)</script>") shouldBe readerDarkPreset.background
    }

    @Test
    fun `a colour that is not a colour falls back to the default`() {
        cssTextColor("red") shouldBe readerDarkPreset.textColor
    }

    @Test
    fun `each alignment the sheet offers is passed through`() {
        listOf("left", "center", "right", "justify").forEach { cssTextAlign(it) shouldBe it }
    }

    @Test
    fun `an alignment that is not one of them falls back to left`() {
        cssTextAlign("left; } </style><script>alert(1)</script>") shouldBe "left"
    }

    @Test
    fun `a family name is passed through`() {
        cssFontFamily("Noto Serif") shouldBe "Noto Serif"
    }

    /** The three generic families are real values here, and a hyphen is what they are spelled with. */
    @Test
    fun `a generic family is passed through`() {
        cssFontFamily("sans-serif") shouldBe "sans-serif"
    }

    @Test
    fun `a family name cannot end the style block`() {
        cssFontFamily("x; } </style><script>alert(1)</script>") shouldBe "x  stylescriptalert1script"
    }

    /** What ends the declaration the name sits in, or the quotes a face or a family value puts round it. */
    @ParameterizedTest
    @ValueSource(strings = [";", "}", "'", "\"", "\\"])
    fun `a family name cannot end the declaration or its quotes`(character: String) {
        cssFontFamily("a${character}b") shouldBe "ab"
    }

    /** Unquoted, a word starting with a digit is not an identifier, and the whole declaration is dropped. */
    @Test
    fun `a family value quotes a name with a digit-led word`() {
        cssFontFamilyValue("Source Sans 3") shouldBe "'Source Sans 3'"
    }

    /** A downloaded font is stored under its file name, which is not a family name at all. */
    @Test
    fun `a family value names a user's font file by its readable name`() {
        cssFontFamilyValue("Source_Sans_3.ttf") shouldBe "'Source Sans 3'"
    }

    /** Quoted, a generic family is a font called "serif", which nothing has. */
    @Test
    fun `a family value leaves a generic family bare`() {
        cssFontFamilyValue("sans-serif") shouldBe "sans-serif"
    }

    @Test
    fun `a family value for no font is empty`() {
        cssFontFamilyValue("") shouldBe ""
    }

    /** An unset font is the reader's own default face, so an empty result must stay empty rather
     *  than becoming a name nothing resolves. */
    @Test
    fun `an empty family stays empty`() {
        cssFontFamily("") shouldBe ""
    }

    /** A picked font lands in the app-private mirror under the name the user gave it, spaces and all. */
    @Test
    fun `a mirror path with a space is usable`() {
        isSafeInCssUrl("file:///data/user/0/app.reikai/files/fonts/Noto Serif.ttf") shouldBe true
    }

    @Test
    fun `a path carrying a quote is not usable`() {
        isSafeInCssUrl("""file:///x/a'); } </style><script>alert(1)</script>.ttf""") shouldBe false
    }
}
