package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.database.models.NovelCategoryImpl
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.novel.models.Novel

class NovelLibrarySearchTest {

    @Test
    fun `empty query returns library unchanged`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Alpha"), item(2, title = "Beta")),
        )
        val result = NovelLibrarySearch.search(library, query = "")
        assertEquals(library, result)
    }

    @Test
    fun `blank query returns library unchanged`() {
        val library = libraryOf("Default" to listOf(item(1, title = "Alpha")))
        val result = NovelLibrarySearch.search(library, query = "   ")
        assertEquals(library, result)
    }

    @Test
    fun `title match is case insensitive`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Solo Leveling"), item(2, title = "Overlord")),
        )
        val result = NovelLibrarySearch.search(library, query = "solo")
        assertEquals(1, result.values.flatten().size)
        assertEquals("Solo Leveling", result.values.flatten().first().libraryNovel.novel.title)
    }

    @Test
    fun `author match works`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Novel A", author = "Chugong")),
        )
        val result = NovelLibrarySearch.search(library, query = "chu")
        assertEquals(1, result.values.flatten().size)
    }

    @Test
    fun `artist match works`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Novel A", artist = "Dubu")),
        )
        val result = NovelLibrarySearch.search(library, query = "dubu")
        assertEquals(1, result.values.flatten().size)
    }

    @Test
    fun `source name match uses precomputed source-name map`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Whatever", source = "royalroad")),
        )
        val result = NovelLibrarySearch.search(
            library,
            query = "royal",
            sourceNames = mapOf("royalroad" to "Royal Road"),
        )
        assertEquals(1, result.values.flatten().size)
    }

    @Test
    fun `single-tag query matches by genre`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "A", genres = listOf("Action", "Adventure")),
                item(2, title = "B", genres = listOf("Romance", "Slice of Life")),
            ),
        )
        val result = NovelLibrarySearch.search(library, query = "Action")
        assertEquals(1, result.values.flatten().size)
        assertEquals(1L, result.values.flatten().first().libraryNovel.novel.id)
    }

    @Test
    fun `comma-separated query requires every fragment to match a genre`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "A", genres = listOf("Action", "Adventure")),
                item(2, title = "B", genres = listOf("Action", "Romance")),
                item(3, title = "C", genres = listOf("Romance", "Slice of Life")),
            ),
        )
        val result = NovelLibrarySearch.search(library, query = "Action, Romance")
        assertEquals(1, result.values.flatten().size)
        assertEquals(2L, result.values.flatten().first().libraryNovel.novel.id)
    }

    @Test
    fun `dash-prefix excludes the tag`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Has yaoi", genres = listOf("Action", "Yaoi")),
                item(2, title = "Clean", genres = listOf("Action", "Adventure")),
            ),
        )
        val result = NovelLibrarySearch.search(library, query = "-Yaoi")
        assertEquals(1, result.values.flatten().size)
        assertEquals(2L, result.values.flatten().first().libraryNovel.novel.id)
    }

    @Test
    fun `categories with no matches are dropped from the result`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "Alpha")),
            "Other" to listOf(item(2, title = "Zeta")),
        )
        val result = NovelLibrarySearch.search(library, query = "alpha")
        assertEquals(1, result.size)
        assertEquals("Default", result.keys.first().name)
    }

    @Test
    fun `blank novel title with non-empty query does not match`() {
        val library = libraryOf("Default" to listOf(item(1, title = "")))
        val result = NovelLibrarySearch.search(library, query = "anything")
        assertTrue(result.isEmpty())
    }

    private fun libraryOf(
        vararg pairs: Pair<String, List<LibraryItem.Novel>>,
    ): Map<NovelCategory, List<LibraryItem.Novel>> =
        pairs.associate { (name, items) ->
            NovelCategoryImpl().apply {
                id = name.hashCode()
                this.name = name
                order = 0
            } to items
        }

    private fun item(
        novelId: Long,
        title: String,
        author: String? = null,
        artist: String? = null,
        genres: List<String>? = null,
        source: String = "test-source",
    ): LibraryItem.Novel {
        val novel = Novel(
            id = novelId,
            source = source,
            url = "/n/$novelId",
            title = title,
            author = author,
            artist = artist,
            description = null,
            genres = genres,
            status = 0,
            thumbnailUrl = null,
            favorite = true,
            lastUpdate = 0L,
            initialized = true,
            chapterFlags = 0,
            dateAdded = 0L,
            updateStrategy = 0,
            coverLastModified = 0L,
        )
        return LibraryItem.Novel(libraryNovel = LibraryNovel(novel = novel))
    }
}
