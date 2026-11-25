package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class EntryInfoOverridesTest {

    private val source = SOURCE

    @Test
    fun `an unchanged form stores nothing`() {
        source.overridesOver(source, unknownStatus = 0L) shouldBe EntryInfoOverrides()
    }

    @Test
    fun `a changed field is stored`() {
        source.copy(title = "Renamed").overridesOver(source, unknownStatus = 0L).title shouldBe "Renamed"
    }

    @Test
    fun `a changed field is stored without its surrounding spaces`() {
        source.copy(title = "  Renamed ").overridesOver(source, unknownStatus = 0L).title shouldBe "Renamed"
    }

    /** Clearing a field, or picking Unknown, is how a user goes back to what the source says. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cleared")
    fun `a cleared field stores nothing, so it follows the source`(field: String, form: EntryEditInfoUi) {
        form.overridesOver(source, unknownStatus = 0L) shouldBe EntryInfoOverrides()
    }

    @Test
    fun `a blank tag is dropped`() {
        source.copy(genre = listOf("Drama", " ")).overridesOver(source, unknownStatus = 0L).genre shouldBe
            listOf("Drama")
    }

    private companion object {
        private val SOURCE = EntryEditInfoUi(
            title = "Title",
            author = "Author",
            artist = "Artist",
            description = "About",
            genre = listOf("Action"),
            status = 1L,
            thumbnailUrl = "https://cover",
            altTitles = emptyList(),
        )

        @JvmStatic
        fun cleared() = listOf(
            Arguments.of("title", SOURCE.copy(title = "")),
            Arguments.of("author", SOURCE.copy(author = " ")),
            Arguments.of("artist", SOURCE.copy(artist = "")),
            Arguments.of("cover", SOURCE.copy(thumbnailUrl = "")),
            Arguments.of("description", SOURCE.copy(description = "  ")),
            Arguments.of("status", SOURCE.copy(status = 0L)),
        )
    }
}
