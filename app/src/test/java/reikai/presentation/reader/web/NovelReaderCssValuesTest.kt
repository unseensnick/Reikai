package reikai.presentation.reader.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A restored backup writes these preference keys, and they land inside the reader document's
 * `<style>`, so the shapes below are what stands between a shared backup file and a script in a page
 * that holds the app's cookies.
 */
class NovelReaderCssValuesTest {

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
}
