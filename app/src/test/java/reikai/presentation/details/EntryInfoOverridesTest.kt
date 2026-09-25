package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EntryInfoOverridesTest {

    private val source = EntryEditInfoUi(
        title = "Title",
        author = "Author",
        artist = "Artist",
        description = "About",
        genre = listOf("Action"),
        status = 1L,
        thumbnailUrl = "https://cover",
    )

    @Test
    fun `an unchanged form stores nothing`() {
        source.overridesOver(source, unknownStatus = 0L) shouldBe EntryInfoOverrides()
    }

    @Test
    fun `a changed field is stored`() {
        source.copy(title = "Renamed").overridesOver(source, unknownStatus = 0L).title shouldBe "Renamed"
    }

    @Test
    fun `a blank tag is dropped`() {
        source.copy(genre = listOf("Drama", " ")).overridesOver(source, unknownStatus = 0L).genre shouldBe
            listOf("Drama")
    }
}
