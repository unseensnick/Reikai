package reikai.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class MangaLewdTest {

    @Test
    fun `genre with an adult tag is lewd`() {
        manga(genre = listOf("Action", "Hentai")).isLewd(sourceName = "MangaDex") shouldBe true
    }

    @Test
    fun `adult tag match is case-insensitive`() {
        manga(genre = listOf("ADULT")).isLewd(sourceName = "MangaDex") shouldBe true
    }

    @Test
    fun `the 18+ tag is lewd`() {
        manga(genre = listOf("18+")).isLewd(sourceName = "MangaDex") shouldBe true
    }

    @Test
    fun `clean genres on a clean source are not lewd`() {
        manga(genre = listOf("Action", "Romance")).isLewd(sourceName = "MangaDex") shouldBe false
    }

    @Test
    fun `a known adult source is lewd even with no genres`() {
        manga(genre = null).isLewd(sourceName = "nhentai") shouldBe true
    }

    @Test
    fun `a clean source with no genres is not lewd`() {
        manga(genre = null).isLewd(sourceName = "MangaDex") shouldBe false
    }

    @Test
    fun `a null source name with clean genres is not lewd`() {
        manga(genre = listOf("Comedy")).isLewd(sourceName = null) shouldBe false
    }

    private fun manga(genre: List<String>?): Manga =
        Manga.create().copy(id = 1L, source = 100L, title = "Test", genre = genre)
}
