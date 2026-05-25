package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.CategoryImpl
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaLibraryGroupingTest {

    @Test
    fun `empty library returns empty`() {
        val result = MangaLibraryGrouping.collapse(emptyMap(), emptySet(), emptySet())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single-item category passes through unchanged`() {
        val library = libraryOf("Default" to listOf(item(1, title = "Only")))
        val result = MangaLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(listOf(1L), result.flatIds())
        assertEquals(0, result.flatItems().first().relatedMangaIds.size)
    }

    @Test
    fun `unique titles with no merges pass through unchanged`() {
        val library = libraryOf(
            "Default" to listOf(item(1, title = "A"), item(2, title = "B"), item(3, title = "C")),
        )
        val result = MangaLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(listOf(1L, 2L, 3L), result.flatIds())
        result.flatItems().forEach { assertEquals(0, it.relatedMangaIds.size) }
    }

    @Test
    fun `same-title auto-merge collapses two manga into one`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "One Piece", totalChapters = 1050),
                item(2, title = "One Piece", totalChapters = 800),
            ),
        )
        val result = MangaLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(listOf(1L), result.flatIds())
        assertArrayEquals(longArrayOf(1, 2), result.flatItems().first().relatedMangaIds.sortedArray())
    }

    @Test
    fun `same-title bucketing is case-insensitive and trims whitespace`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Berserk", totalChapters = 400),
                item(2, title = "  berserk  ", totalChapters = 380),
            ),
        )
        val result = MangaLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(1, result.flatItems().size)
        assertEquals(2, result.flatItems().first().relatedMangaIds.size)
    }

    @Test
    fun `manual merge collapses two manga regardless of differing titles`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Alpha", totalChapters = 50),
                item(2, title = "Beta", totalChapters = 100),
            ),
        )
        val result = MangaLibraryGrouping.collapse(
            library = library,
            manualMerges = setOf("1,2"),
            manualUnmerges = emptySet(),
        )
        // Primary is the one with more totalChapters (id 2). Group carries both IDs.
        assertEquals(listOf(2L), result.flatIds())
        assertArrayEquals(longArrayOf(1, 2), result.flatItems().first().relatedMangaIds.sortedArray())
    }

    @Test
    fun `manual unmerge keeps two same-title manga as separate entries`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Naruto", totalChapters = 700),
                item(2, title = "Naruto", totalChapters = 700),
            ),
        )
        val result = MangaLibraryGrouping.collapse(
            library = library,
            manualMerges = emptySet(),
            manualUnmerges = setOf("1,2"),
        )
        // Both went into the "naruto" bucket but the unmerge split them into singletons.
        assertEquals(listOf(1L, 2L), result.flatIds())
        result.flatItems().forEach { assertEquals(0, it.relatedMangaIds.size) }
    }

    @Test
    fun `primary is chosen by highest totalChapters`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Bleach", totalChapters = 400),
                item(2, title = "Bleach", totalChapters = 700),
                item(3, title = "Bleach", totalChapters = 200),
            ),
        )
        val result = MangaLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(listOf(2L), result.flatIds())
        assertEquals(3, result.flatItems().first().relatedMangaIds.size)
    }

    @Test
    fun `primary tiebreaker on chapter match is the most recently added`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "X", totalChapters = 100, dateAdded = 2_000L),
                item(2, title = "X", totalChapters = 100, dateAdded = 1_000L),
            ),
        )
        // Legacy: compareBy(totalChapters).thenBy(date_added) + maxWith picks the LARGER
        // date_added on chapter ties (most recently added wins as primary).
        val result = MangaLibraryGrouping.collapse(library, emptySet(), emptySet())
        assertEquals(listOf(1L), result.flatIds())
    }

    @Test
    fun `three same-title with unmerge pair splits into two subgroups`() {
        val library = libraryOf(
            "Default" to listOf(
                item(1, title = "Eden", totalChapters = 100),
                item(2, title = "Eden", totalChapters = 200),
                item(3, title = "Eden", totalChapters = 300),
            ),
        )
        // Greedy placement: 1 -> [1]; 2 tries [1] (1,2 incompatible) -> [2]; 3 tries [1] ->
        // compatible -> [1, 3]. Result subgroups: [1, 3] and [2].
        val result = MangaLibraryGrouping.collapse(
            library = library,
            manualMerges = emptySet(),
            manualUnmerges = setOf("1,2"),
        )
        // Primary of [1, 3] is 3 (300 chapters > 100). Primary of [2] is 2.
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
        val result = MangaLibraryGrouping.collapse(library, emptySet(), emptySet())
        // Each category collapses its own pair into one entry. No cross-category grouping.
        result.forEach { (_, items) ->
            assertEquals(1, items.size)
            assertEquals(2, items.first().relatedMangaIds.size)
        }
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
        // 1 and 2 are merged; 3 has the same title as 2 but is not in the merge entry. So 1+2
        // collapse via merge-key bucket "1,2"; 3 lands alone in the "beta" bucket.
        val result = MangaLibraryGrouping.collapse(
            library = library,
            manualMerges = setOf("1,2"),
            manualUnmerges = emptySet(),
        )
        val ids = result.flatIds()
        assertEquals(listOf(2L, 3L), ids)
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun Map<Category, List<LibraryItem.Manga>>.flatIds(): List<Long> =
        values.flatten().mapNotNull { it.libraryManga.manga.id }.sorted()

    private fun Map<Category, List<LibraryItem.Manga>>.flatItems(): List<LibraryItem.Manga> =
        values.flatten()

    private fun libraryOf(
        vararg pairs: Pair<String, List<LibraryItem.Manga>>,
    ): Map<Category, List<LibraryItem.Manga>> = pairs.associate { (name, items) ->
        CategoryImpl().apply {
            id = name.hashCode()
            this.name = name
            order = 0
        } to items
    }

    private fun item(
        mangaId: Long,
        title: String = "title-$mangaId",
        totalChapters: Int = 0,
        dateAdded: Long = 0L,
    ): LibraryItem.Manga {
        val manga = MangaImpl(id = mangaId, source = 100L, url = "url-$mangaId").apply {
            ogTitle = title
            date_added = dateAdded
        }
        return LibraryItem.Manga(
            libraryManga = LibraryManga(manga = manga, totalChapters = totalChapters),
        )
    }
}
