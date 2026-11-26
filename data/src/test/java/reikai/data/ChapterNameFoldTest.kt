package reikai.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ChapterNameFoldTest {

    /**
     * What `contains(ignoreCase = true)` answers on Android for each pair, measured on the emulator. The JVM
     * folds a surrogate pair where Android does not, so the table is the expectation rather than that call.
     */
    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(
        "ГЛАВА 1, глава, true",
        "ΣΟΦΙΑ, σοφια, true",
        "ς, σ, true",
        "İSTANBUL, istanbul, true",
        "ISTANBUL, ıstanbul, true",
        "ẞ, ß, true",
        "STRASSE, straße, false",
        "\u212Aelvin, KELVIN, true",
        "ſun, SUN, true",
        "ǅ, ǆ, true",
        "\uD801\uDC00, \uD801\uDC28, false",
    )
    fun `a folded term is found in a folded name exactly when Android matches them ignoring case`(
        name: String,
        term: String,
        matches: Boolean,
    ) {
        foldCase(name).contains(foldCase(term)) shouldBe matches
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "Chapter 12, false",
        "第1話, false",
        "глава, false",
        "Глава, true",
        "\u212Aelvin, true",
    )
    fun `a name stores a fold only when a non-ASCII char folds`(name: String, stored: Boolean) {
        (chapterNameFold(name) != null) shouldBe stored
    }
}
