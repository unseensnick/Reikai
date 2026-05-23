package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.CategoryImpl
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.domain.manga.models.Manga
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaLibrarySectionerTest {

    @Test
    fun `empty library and no categories returns empty map`() {
        val result = MangaLibrarySectioner.section(
            libraryManga = emptyList(),
            userCategories = emptyList(),
            defaultCategory = defaultCategory(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single manga in default category appears under default category`() {
        val result = MangaLibrarySectioner.section(
            libraryManga = listOf(libraryManga(1, "Alpha", categoryId = 0)),
            userCategories = emptyList(),
            defaultCategory = defaultCategory(),
        )
        assertEquals(1, result.size)
        val entry = result.entries.first()
        assertEquals(0, entry.key.id)
        assertEquals(1, entry.value.size)
        assertEquals(1L, entry.value[0].libraryManga.manga.id)
    }

    @Test
    fun `default category is not injected when no manga uses it`() {
        val result = MangaLibrarySectioner.section(
            libraryManga = listOf(libraryManga(1, "Alpha", categoryId = 5)),
            userCategories = listOf(userCategory(5, "Reading", order = 0)),
            defaultCategory = defaultCategory(),
        )
        assertEquals(1, result.size)
        assertEquals(5, result.entries.first().key.id)
    }

    @Test
    fun `manga within a category are sorted by title case insensitively`() {
        val result = MangaLibrarySectioner.section(
            libraryManga = listOf(
                libraryManga(1, "Charlie", categoryId = 0),
                libraryManga(2, "alpha", categoryId = 0),
                libraryManga(3, "Bravo", categoryId = 0),
            ),
            userCategories = emptyList(),
            defaultCategory = defaultCategory(),
        )
        val titles = result.entries.first().value.map { it.libraryManga.manga.title }
        assertEquals(listOf("alpha", "Bravo", "Charlie"), titles)
    }

    @Test
    fun `default category appears first then user categories by order field`() {
        val result = MangaLibrarySectioner.section(
            libraryManga = listOf(
                libraryManga(1, "X", categoryId = 0),
                libraryManga(2, "Y", categoryId = 10),
                libraryManga(3, "Z", categoryId = 20),
            ),
            userCategories = listOf(
                userCategory(20, "Second", order = 1),
                userCategory(10, "First", order = 0),
            ),
            defaultCategory = defaultCategory(),
        )
        val categoryIds = result.keys.map { it.id }
        assertEquals(listOf(0, 10, 20), categoryIds)
    }

    @Test
    fun `duplicate manga ids are deduped within a category`() {
        val result = MangaLibrarySectioner.section(
            libraryManga = listOf(
                libraryManga(1, "Alpha", categoryId = 0),
                libraryManga(1, "Alpha", categoryId = 0),
            ),
            userCategories = emptyList(),
            defaultCategory = defaultCategory(),
        )
        assertEquals(1, result.entries.first().value.size)
    }

    @Test
    fun `empty user category is still included as an empty list`() {
        val result = MangaLibrarySectioner.section(
            libraryManga = listOf(libraryManga(1, "Alpha", categoryId = 5)),
            userCategories = listOf(
                userCategory(5, "Reading", order = 0),
                userCategory(6, "Completed", order = 1),
            ),
            defaultCategory = defaultCategory(),
        )
        assertEquals(2, result.size)
        val empty = result.entries.find { it.key.id == 6 }
        assertNotNull(empty)
        assertTrue(empty!!.value.isEmpty())
    }

    @Test
    fun `categories with null id are filtered out of user list`() {
        val result = MangaLibrarySectioner.section(
            libraryManga = listOf(libraryManga(1, "Alpha", categoryId = 5)),
            userCategories = listOf(
                userCategory(5, "Reading", order = 0),
                userCategoryWithNullId("Orphan", order = 1),
            ),
            defaultCategory = defaultCategory(),
        )
        assertEquals(1, result.size)
        assertEquals(5, result.entries.first().key.id)
    }

    private fun defaultCategory() = CategoryImpl().apply {
        id = 0
        name = "Default"
        order = -1
        isSystem = true
    }

    private fun userCategory(id: Int, name: String, order: Int) = CategoryImpl().apply {
        this.id = id
        this.name = name
        this.order = order
    }

    private fun userCategoryWithNullId(name: String, order: Int) = CategoryImpl().apply {
        this.id = null
        this.name = name
        this.order = order
    }

    private fun libraryManga(
        mangaId: Long,
        title: String,
        categoryId: Int = 0,
    ): LibraryManga {
        val mockManga = mockk<Manga>(relaxed = true)
        every { mockManga.id } returns mangaId
        every { mockManga.title } returns title
        return LibraryManga(manga = mockManga, category = categoryId)
    }
}
