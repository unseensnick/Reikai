package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategoryImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.novel.models.Novel

class NovelLibrarySectionerTest {

    @Test
    fun `empty library and no categories returns empty map`() {
        val result = NovelLibrarySectioner.section(
            libraryNovel = emptyList(),
            userCategories = emptyList(),
            defaultCategory = defaultCategory(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single novel in default category appears under default category`() {
        val result = NovelLibrarySectioner.section(
            libraryNovel = listOf(libraryNovel(1, "Alpha", categoryId = 0)),
            userCategories = emptyList(),
            defaultCategory = defaultCategory(),
        )
        assertEquals(1, result.size)
        val entry = result.entries.first()
        assertEquals(0, entry.key.id)
        assertEquals(1, entry.value.size)
        assertEquals(1L, entry.value[0].libraryNovel.novel.id)
    }

    @Test
    fun `default category is not injected when no novel uses it`() {
        val result = NovelLibrarySectioner.section(
            libraryNovel = listOf(libraryNovel(1, "Alpha", categoryId = 5)),
            userCategories = listOf(userCategory(5, "Reading", order = 0)),
            defaultCategory = defaultCategory(),
        )
        assertEquals(1, result.size)
        assertEquals(5, result.entries.first().key.id)
    }

    @Test
    fun `novels within a category preserve insertion order (sorting is downstream)`() {
        val result = NovelLibrarySectioner.section(
            libraryNovel = listOf(
                libraryNovel(1, "Charlie", categoryId = 0),
                libraryNovel(2, "alpha", categoryId = 0),
                libraryNovel(3, "Bravo", categoryId = 0),
            ),
            userCategories = emptyList(),
            defaultCategory = defaultCategory(),
        )
        val ids = result.entries.first().value.map { it.libraryNovel.novel.id }
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun `categorySortOrder 1 sorts user categories alphabetically with default pinned at top`() {
        val result = NovelLibrarySectioner.section(
            libraryNovel = listOf(
                libraryNovel(1, "X", categoryId = 0),
                libraryNovel(2, "Y", categoryId = 10),
                libraryNovel(3, "Z", categoryId = 20),
            ),
            userCategories = listOf(
                userCategory(10, "Reading", order = 0),
                userCategory(20, "Action", order = 1),
            ),
            defaultCategory = defaultCategory(),
            categorySortOrder = 1,
        )
        val ids = result.keys.map { it.id }
        assertEquals(listOf(0, 20, 10), ids)
    }

    @Test
    fun `categorySortOrder 2 sorts user categories Z to A with default pinned at top`() {
        val result = NovelLibrarySectioner.section(
            libraryNovel = listOf(
                libraryNovel(1, "X", categoryId = 0),
                libraryNovel(2, "Y", categoryId = 10),
                libraryNovel(3, "Z", categoryId = 20),
            ),
            userCategories = listOf(
                userCategory(10, "Action", order = 0),
                userCategory(20, "Reading", order = 1),
            ),
            defaultCategory = defaultCategory(),
            categorySortOrder = 2,
        )
        val ids = result.keys.map { it.id }
        assertEquals(listOf(0, 20, 10), ids)
    }

    @Test
    fun `categorySortOrder defaults to manual when 0 or unspecified`() {
        val result = NovelLibrarySectioner.section(
            libraryNovel = listOf(
                libraryNovel(1, "Y", categoryId = 10),
                libraryNovel(2, "Z", categoryId = 20),
            ),
            userCategories = listOf(
                userCategory(20, "Action", order = 1),
                userCategory(10, "Reading", order = 0),
            ),
            defaultCategory = defaultCategory(),
            categorySortOrder = 0,
        )
        val ids = result.keys.map { it.id }
        assertEquals(listOf(10, 20), ids)
    }

    @Test
    fun `default category appears first then user categories by order field`() {
        val result = NovelLibrarySectioner.section(
            libraryNovel = listOf(
                libraryNovel(1, "X", categoryId = 0),
                libraryNovel(2, "Y", categoryId = 10),
                libraryNovel(3, "Z", categoryId = 20),
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
    fun `duplicate novel ids are deduped within a category`() {
        val result = NovelLibrarySectioner.section(
            libraryNovel = listOf(
                libraryNovel(1, "Alpha", categoryId = 0),
                libraryNovel(1, "Alpha", categoryId = 0),
            ),
            userCategories = emptyList(),
            defaultCategory = defaultCategory(),
        )
        assertEquals(1, result.entries.first().value.size)
    }

    @Test
    fun `empty user category is still included as an empty list`() {
        val result = NovelLibrarySectioner.section(
            libraryNovel = listOf(libraryNovel(1, "Alpha", categoryId = 5)),
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
        val result = NovelLibrarySectioner.section(
            libraryNovel = listOf(libraryNovel(1, "Alpha", categoryId = 5)),
            userCategories = listOf(
                userCategory(5, "Reading", order = 0),
                userCategoryWithNullId("Orphan", order = 1),
            ),
            defaultCategory = defaultCategory(),
        )
        assertEquals(1, result.size)
        assertEquals(5, result.entries.first().key.id)
    }

    private fun defaultCategory() = NovelCategoryImpl().apply {
        id = 0
        name = "Default"
        order = -1
        isSystem = true
    }

    private fun userCategory(id: Int, name: String, order: Int) = NovelCategoryImpl().apply {
        this.id = id
        this.name = name
        this.order = order
    }

    private fun userCategoryWithNullId(name: String, order: Int) = NovelCategoryImpl().apply {
        this.id = null
        this.name = name
        this.order = order
    }

    private fun libraryNovel(
        novelId: Long,
        title: String,
        categoryId: Int = 0,
    ): LibraryNovel = LibraryNovel(
        novel = Novel(
            id = novelId,
            source = "test-source",
            url = "/n/$novelId",
            title = title,
            author = null,
            artist = null,
            description = null,
            genres = null,
            status = 0,
            thumbnailUrl = null,
            favorite = true,
            lastUpdate = 0L,
            initialized = true,
            chapterFlags = 0,
            dateAdded = 0L,
            updateStrategy = 0,
            coverLastModified = 0L,
        ),
        category = categoryId,
    )
}
