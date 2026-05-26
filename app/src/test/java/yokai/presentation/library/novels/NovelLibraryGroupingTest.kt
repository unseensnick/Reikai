package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.database.models.NovelCategoryImpl
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.novel.models.Novel

class NovelLibraryGroupingTest {

    @Test
    fun `empty library returns empty`() {
        val result = NovelLibraryGrouping.collapse(emptyMap(), emptySet(), emptySet())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single-item category passes through unchanged`() {
        val library = libraryOf("Default" to listOf(item(1, title = "Only")))
        val result = NovelLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(listOf(1L), result.flatIds())
        assertEquals(0, result.flatItems().first().relatedNovelIds.size)
    }

    @Test
    fun `unique titles with no merges pass through unchanged`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "A"), item(2, title = "B"), item(3, title = "C")),
        )
        val result = NovelLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(listOf(1L, 2L, 3L), result.flatIds())
        result.flatItems().forEach { assertEquals(0, it.relatedNovelIds.size) }
    }

    @Test
    fun `same-title auto-merge collapses two novels into one`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Overlord", totalChapters = 1050),
                item(2, title = "Overlord", totalChapters = 800),
            ),
        )
        val result = NovelLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(listOf(1L), result.flatIds())
        assertArrayEquals(longArrayOf(1, 2), result.flatItems().first().relatedNovelIds.sortedArray())
    }

    @Test
    fun `same-title bucketing is case-insensitive and trims whitespace`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Mushoku Tensei", totalChapters = 400),
                item(2, title = "  mushoku tensei  ", totalChapters = 380),
            ),
        )
        val result = NovelLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(1, result.flatItems().size)
        assertEquals(2, result.flatItems().first().relatedNovelIds.size)
    }

    @Test
    fun `manual merge collapses two novels regardless of differing titles`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Alpha", totalChapters = 50),
                item(2, title = "Beta", totalChapters = 100),
            ),
        )
        val result = NovelLibraryGrouping.collapse(
            library = library,
            manualMerges = setOf("1,2"),
            manualUnmerges = emptySet(),
        )
        assertEquals(listOf(2L), result.flatIds())
        assertArrayEquals(longArrayOf(1, 2), result.flatItems().first().relatedNovelIds.sortedArray())
    }

    @Test
    fun `manual unmerge keeps two same-title novels as separate entries`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Re Zero", totalChapters = 700),
                item(2, title = "Re Zero", totalChapters = 700),
            ),
        )
        val result = NovelLibraryGrouping.collapse(
            library = library,
            manualMerges = emptySet(),
            manualUnmerges = setOf("1,2"),
        )
        assertEquals(listOf(1L, 2L), result.flatIds())
        result.flatItems().forEach { assertEquals(0, it.relatedNovelIds.size) }
    }

    @Test
    fun `primary is chosen by highest totalChapters`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Konosuba", totalChapters = 400),
                item(2, title = "Konosuba", totalChapters = 700),
                item(3, title = "Konosuba", totalChapters = 200),
            ),
        )
        val result = NovelLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(listOf(2L), result.flatIds())
        assertEquals(3, result.flatItems().first().relatedNovelIds.size)
    }

    @Test
    fun `primary tiebreaker on chapter match is the most recently added`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "X", totalChapters = 100, dateAdded = 2_000L),
                item(2, title = "X", totalChapters = 100, dateAdded = 1_000L),
            ),
        )
        val result = NovelLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `three same-title with unmerge pair splits into two subgroups`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Goblin Slayer", totalChapters = 100),
                item(2, title = "Goblin Slayer", totalChapters = 200),
                item(3, title = "Goblin Slayer", totalChapters = 300),
            ),
        )
        val result = NovelLibraryGrouping.collapse(
            library = library,
            manualMerges = emptySet(),
            manualUnmerges = setOf("1,2"),
        )
        val ids = result.flatIds()
        assertEquals(listOf(2L, 3L), ids)
    }

    @Test
    fun `grouping happens per category independently`() {
        val library = libraryOf(
            "Cat1" to listOf(
                item(1, title = "Same", totalChapters = 100),
                item(2, title = "Same", totalChapters = 200),
            ),
            "Cat2" to listOf(
                item(3, title = "Same", totalChapters = 300),
                item(4, title = "Same", totalChapters = 400),
            ),
        )
        val result = NovelLibraryGrouping.collapse(library, emptySet(), emptySet())
        result.forEach { (_, items) ->
            assertEquals(1, items.size)
            assertEquals(2, items.first().relatedNovelIds.size)
        }
    }

    @Test
    fun `auto-merge can be disabled to keep same-title novels as separate cards`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Re Zero", totalChapters = 700),
                item(2, title = "Re Zero", totalChapters = 700),
            ),
        )
        val result = NovelLibraryGrouping.collapse(
            library = library,
            manualMerges = emptySet(),
            manualUnmerges = emptySet(),
            autoMergeSameTitle = false,
        )
        assertEquals(listOf(1L, 2L), result.flatIds())
        result.flatItems().forEach { assertEquals(0, it.relatedNovelIds.size) }
    }

    @Test
    fun `manual merges still apply when auto-merge is disabled`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Alpha", totalChapters = 50),
                item(2, title = "Beta", totalChapters = 100),
            ),
        )
        val result = NovelLibraryGrouping.collapse(
            library = library,
            manualMerges = setOf("1,2"),
            manualUnmerges = emptySet(),
            autoMergeSameTitle = false,
        )
        assertEquals(listOf(2L), result.flatIds())
        assertEquals(2, result.flatItems().first().relatedNovelIds.size)
    }

    @Test
    fun `merge key takes priority over title bucket for items in the merge`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Alpha", totalChapters = 50),
                item(2, title = "Beta", totalChapters = 100),
                item(3, title = "Beta", totalChapters = 25),
            ),
        )
        val result = NovelLibraryGrouping.collapse(
            library = library,
            manualMerges = setOf("1,2"),
            manualUnmerges = emptySet(),
        )
        val ids = result.flatIds()
        assertEquals(listOf(2L, 3L), ids)
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun Map<NovelCategory, List<LibraryItem.Novel>>.flatIds(): List<Long> =
        values.flatten().mapNotNull { it.libraryNovel.novel.id }.sorted()

    private fun Map<NovelCategory, List<LibraryItem.Novel>>.flatItems(): List<LibraryItem.Novel> =
        values.flatten()

    private fun libraryOf(
        vararg pairs: Pair<String, List<LibraryItem.Novel>>,
    ): Map<NovelCategory, List<LibraryItem.Novel>> = pairs.associate { (name, items) ->
        NovelCategoryImpl().apply {
            id = name.hashCode()
            this.name = name
            order = 0
        } to items
    }

    private fun item(
        novelId: Long,
        title: String = "title-$novelId",
        totalChapters: Int = 0,
        dateAdded: Long = 0L,
    ): LibraryItem.Novel {
        val novel = Novel(
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
            dateAdded = dateAdded,
            updateStrategy = 0,
            coverLastModified = 0L,
        )
        return LibraryItem.Novel(
            libraryNovel = LibraryNovel(novel = novel, totalChapters = totalChapters),
        )
    }
}
