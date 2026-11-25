package reikai.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class MangaLewdTest {

    /** [sourceName] null is a novel, whose rule reads no source names. */
    class Case(val name: String, val genres: List<String>?, val sourceName: String?, val isAdult: Boolean) {
        override fun toString() = name
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `the adult rule over genres and source names`(case: Case) {
        isAdultEntry(adultSource = false, sourceName = case.sourceName, genres = case.genres) shouldBe case.isAdult
    }

    @Test
    fun `an adult source is lewd with clean genres`() {
        isAdultEntry(adultSource = true, sourceName = null, genres = listOf("Comedy")) shouldBe true
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Case("an adult tag", listOf("Action", "Hentai"), "MangaDex", true),
            Case("a tag in another case", listOf("ADULT"), "MangaDex", true),
            Case("the 18+ tag", listOf("18+"), "MangaDex", true),
            Case("clean genres on a clean source", listOf("Action", "Romance"), "MangaDex", false),
            Case("a known adult source with no genres", null, "nhentai", true),
            Case("the Hitomi source by its name", null, "Hitomi", true),
            Case("a clean source with no genres", null, "MangaDex", false),
            Case("a novel with clean genres", listOf("Comedy"), null, false),
            Case("a manga tagged only Mature", listOf("Action", "Mature"), "MangaDex", false),
            Case("a novel tagged only Mature", listOf("Fantasy", "Mature"), null, false),
            Case("a novel tagged Smut", listOf("Mature", "Smut"), null, true),
            Case("a novel tagged Young Adult", listOf("Fantasy", "Young Adult"), null, false),
            Case("a manga tagged Adult Cast", listOf("Drama", "Adult Cast"), "MangaDex", false),
        )
    }
}
