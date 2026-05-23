package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.CategoryImpl
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaLibrarySearchTest {

    @Test
    fun `empty query returns library unchanged`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Alpha"), item(2, title = "Beta")),
        )
        val result = MangaLibrarySearch.search(library, query = "")
        assertEquals(library, result)
    }

    @Test
    fun `blank query returns library unchanged`() {
        val library = libraryOf("Default" to listOf(item(1, title = "Alpha")))
        val result = MangaLibrarySearch.search(library, query = "   ")
        assertEquals(library, result)
    }

    @Test
    fun `title match is case insensitive`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Berserk"), item(2, title = "Bleach")),
        )
        val result = MangaLibrarySearch.search(library, query = "ber")
        assertEquals(1, result.values.flatten().size)
        assertEquals("Berserk", result.values.flatten().first().libraryManga.manga.title)
    }

    @Test
    fun `author match works`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Manga A", author = "Miura Kentaro")),
        )
        val result = MangaLibrarySearch.search(library, query = "miura")
        assertEquals(1, result.values.flatten().size)
    }

    @Test
    fun `artist match works`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Manga A", artist = "Studio Ghibli")),
        )
        val result = MangaLibrarySearch.search(library, query = "ghibli")
        assertEquals(1, result.values.flatten().size)
    }

    @Test
    fun `source name match uses precomputed source-name map`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Whatever", sourceId = 42L)),
        )
        val result = MangaLibrarySearch.search(
            library,
            query = "mangadex",
            sourceNames = mapOf(42L to "MangaDex"),
        )
        assertEquals(1, result.values.flatten().size)
    }

    @Test
    fun `single-tag query matches by genre`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "A", genre = "Action, Adventure"),
                item(2, title = "B", genre = "Romance, Slice of Life"),
            ),
        )
        val result = MangaLibrarySearch.search(library, query = "Action")
        assertEquals(1, result.values.flatten().size)
        assertEquals(1L, result.values.flatten().first().libraryManga.manga.id)
    }

    @Test
    fun `comma-separated query requires every fragment to match a genre`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "A", genre = "Action, Adventure"),
                item(2, title = "B", genre = "Action, Romance"),
                item(3, title = "C", genre = "Romance, Slice of Life"),
            ),
        )
        val result = MangaLibrarySearch.search(library, query = "Action, Romance")
        assertEquals(1, result.values.flatten().size)
        assertEquals(2L, result.values.flatten().first().libraryManga.manga.id)
    }

    @Test
    fun `dash-prefix excludes the tag`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Has yaoi", genre = "Action, Yaoi"),
                item(2, title = "Clean", genre = "Action, Adventure"),
            ),
        )
        val result = MangaLibrarySearch.search(library, query = "-Yaoi")
        // -Yaoi means "must NOT have Yaoi tag"; the genre-only check requires the manga to have a
        // genre list to evaluate against, and that filter passes for item 2.
        assertEquals(1, result.values.flatten().size)
        assertEquals(2L, result.values.flatten().first().libraryManga.manga.id)
    }

    @Test
    fun `categories with no matches are dropped from the result`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Alpha")),
            "Other" to listOf(item(2, title = "Zeta")),
        )
        val result = MangaLibrarySearch.search(library, query = "alpha")
        assertEquals(1, result.size)
        assertEquals("Default", result.keys.first().name)
    }

    @Test
    fun `blank manga title with non-empty query does not match`() {
        val library = libraryOf("Default" to listOf(item(1, title = "")))
        val result = MangaLibrarySearch.search(library, query = "anything")
        assertTrue(result.isEmpty())
    }

    private fun libraryOf(
        vararg pairs: Pair<String, List<LibraryItem.Manga>>,
    ): Map<eu.kanade.tachiyomi.data.database.models.Category, List<LibraryItem.Manga>> =
        pairs.associate { (name, items) ->
            CategoryImpl().apply {
                id = name.hashCode()
                this.name = name
                order = 0
            } to items
        }

    private fun item(
        mangaId: Long,
        title: String,
        author: String? = null,
        artist: String? = null,
        genre: String? = null,
        sourceId: Long = 0,
    ): LibraryItem.Manga {
        val mockManga = mockk<Manga>(relaxed = true)
        every { mockManga.id } returns mangaId
        every { mockManga.title } returns title
        every { mockManga.author } returns author
        every { mockManga.artist } returns artist
        every { mockManga.genre } returns genre
        every { mockManga.source } returns sourceId
        return LibraryItem.Manga(libraryManga = LibraryManga(manga = mockManga))
    }
}
