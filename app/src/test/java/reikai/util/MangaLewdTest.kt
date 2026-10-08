package reikai.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaLewdTest {

    @Test
    fun `genre with an adult tag is lewd`() {
        isLewd(genre = listOf("Action", "Hentai"), sourceName = "MangaDex") shouldBe true
    }

    @Test
    fun `adult tag match is case-insensitive`() {
        isLewd(genre = listOf("ADULT"), sourceName = "MangaDex") shouldBe true
    }

    @Test
    fun `the 18+ tag is lewd`() {
        isLewd(genre = listOf("18+"), sourceName = "MangaDex") shouldBe true
    }

    @Test
    fun `clean genres on a clean source are not lewd`() {
        isLewd(genre = listOf("Action", "Romance"), sourceName = "MangaDex") shouldBe false
    }

    @Test
    fun `a known adult source is lewd even with no genres`() {
        isLewd(genre = null, sourceName = "nhentai") shouldBe true
    }

    @Test
    fun `a clean source with no genres is not lewd`() {
        isLewd(genre = null, sourceName = "MangaDex") shouldBe false
    }

    @Test
    fun `a null source name with clean genres is not lewd`() {
        isLewd(genre = listOf("Comedy"), sourceName = null) shouldBe false
    }

    @Test
    fun `an adult source is lewd with clean genres`() {
        isAdultEntry(adultSource = true, sourceName = null, genres = listOf("Comedy")) shouldBe true
    }

    private fun isLewd(genre: List<String>?, sourceName: String?) =
        isAdultEntry(adultSource = false, sourceName = sourceName, genres = genre)
}
