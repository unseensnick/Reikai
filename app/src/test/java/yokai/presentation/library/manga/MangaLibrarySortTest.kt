package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.CategoryImpl
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.ui.library.LibrarySort
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaLibrarySortTest {

    // --- baseline behavior ---------------------------------------------------------------------

    @Test
    fun `empty library returns empty`() {
        val result = MangaLibrarySort.sort(
            library = emptyMap(),
            libraryDefaultMode = LibrarySort.Title,
            libraryDefaultAscending = true,
            randomSeed = 0L,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single-item category passes through unchanged`() {
        val library = libraryOf(category("Default", sort = 'a') to listOf(item(1, title = "Only")))
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(1L), result.flatIds())
    }

    // --- Title mode ----------------------------------------------------------------------------

    @Test
    fun `Title mode ascending sorts A to Z`() {
        val library = libraryOf(
            category("Default", sort = 'a') to listOf(
                item(1, title = "Charlie"),
                item(2, title = "Alpha"),
                item(3, title = "Bravo"),
            ),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    @Test
    fun `Title mode descending sorts Z to A`() {
        val library = libraryOf(
            // 'b' = descending Title (per LibrarySort.categoryValueDescending for value 0).
            category("Default", sort = 'b') to listOf(
                item(1, title = "Alpha"),
                item(2, title = "Bravo"),
                item(3, title = "Charlie"),
            ),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(3L, 2L, 1L), result.flatIdsInOrder())
    }

    @Test
    fun `Title mode honors removeArticles flag`() {
        // "The Aardvark" vs "Banana": without article-stripping, "Banana" < "The Aardvark";
        // with stripping, "Aardvark" < "Banana". Different orderings.
        val library = libraryOf(
            category("Default", sort = 'a') to listOf(
                item(1, title = "The Aardvark"),
                item(2, title = "Banana"),
            ),
        )
        val withArticles = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L, removeArticles = false)
        assertEquals(listOf(2L, 1L), withArticles.flatIdsInOrder())
        val withoutArticles = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L, removeArticles = true)
        assertEquals(listOf(1L, 2L), withoutArticles.flatIdsInOrder())
    }

    // --- TotalChapters / DateAdded / LatestChapter / LastRead / DateFetched --------------------

    @Test
    fun `TotalChapters ascending puts fewer chapters first`() {
        val library = libraryOf(
            category("Default", sort = 'i') to listOf(
                item(1, totalChapters = 100),
                item(2, totalChapters = 10),
                item(3, totalChapters = 50),
            ),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    @Test
    fun `TotalChapters descending puts more chapters first`() {
        val library = libraryOf(
            category("Default", sort = 'j') to listOf(
                item(1, totalChapters = 100),
                item(2, totalChapters = 10),
                item(3, totalChapters = 50),
            ),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(1L, 3L, 2L), result.flatIdsInOrder())
    }

    @Test
    fun `DateAdded ascending puts older items first`() {
        val library = libraryOf(
            category("Default", sort = 'k') to listOf(
                item(1, dateAdded = 3_000L),
                item(2, dateAdded = 1_000L),
                item(3, dateAdded = 2_000L),
            ),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    @Test
    fun `LatestChapter ascending puts oldest update first`() {
        // LatestChapter overrides catValue to 1 → categoryValue = 'a' + 1*2 = 'c' asc, 'd' desc.
        val library = libraryOf(
            category("Default", sort = 'c') to listOf(
                item(1, latestUpdate = 3_000L),
                item(2, latestUpdate = 1_000L),
                item(3, latestUpdate = 2_000L),
            ),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    // --- Unread mode (special 0-sink behavior) -------------------------------------------------

    @Test
    fun `Unread mode ascending sinks 0-unread items to the bottom`() {
        // Unread overrides catValue to 2 → categoryValue = 'a' + 2*2 = 'e' asc, 'f' desc.
        val library = libraryOf(
            category("Default", sort = 'e') to listOf(
                item(1, unread = 0),
                item(2, unread = 5),
                item(3, unread = 0),
                item(4, unread = 10),
            ),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        // Ascending: 5 before 10, both before any 0-unread.
        val ids = result.flatIdsInOrder()
        assertEquals(listOf(2L, 4L), ids.take(2))
        // The 0-unread pair {1, 3} should be last; title tiebreaker orders them.
        assertEquals(setOf(1L, 3L), ids.takeLast(2).toSet())
    }

    @Test
    fun `Unread mode descending still sinks 0-unread items to the bottom`() {
        val library = libraryOf(
            category("Default", sort = 'f') to listOf(
                item(1, unread = 0),
                item(2, unread = 5),
                item(3, unread = 10),
            ),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        // Descending: 10 before 5, 0 still last.
        assertEquals(listOf(3L, 2L, 1L), result.flatIdsInOrder())
    }

    // --- DragAndDrop mode ----------------------------------------------------------------------

    @Test
    fun `explicit DragAndDrop on non-dynamic category falls back to alphabetical`() {
        val library = libraryOf(
            // 'D' = explicit DragAndDrop mode.
            category("Default", sort = 'D') to listOf(
                item(1, title = "Charlie"),
                item(2, title = "Alpha"),
                item(3, title = "Bravo"),
            ),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        // Legacy quirk: explicit DragAndDrop on a non-dynamic category renders alphabetically
        // because the real drag-and-drop position is consulted only via the mangaOrder fallback.
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    @Test
    fun `DragAndDrop on dynamic category sorts by manga's original category order`() {
        val library = libraryOf(
            category(
                name = "Webtoons",
                sort = 'D',
                isDynamic = true,
            ) to listOf(
                item(1, title = "A", mangaCategory = 30),
                item(2, title = "B", mangaCategory = 10),
                item(3, title = "C", mangaCategory = 20),
            ),
            category(name = "Cat10", id = 10, order = 0) to emptyList(),
            category(name = "Cat20", id = 20, order = 1) to emptyList(),
            category(name = "Cat30", id = 30, order = 2) to emptyList(),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        // Webtoons dynamic items sort by underlying category order (10 → 20 → 30).
        val webtoonsIds = result.entries
            .first { it.key.name == "Webtoons" }
            .value.mapNotNull { it.libraryManga.manga.id }
        assertEquals(listOf(2L, 3L, 1L), webtoonsIds)
    }

    // --- mangaOrder fallback (no explicit sort + populated mangaOrder) -------------------------

    @Test
    fun `mangaOrder fallback respects user-defined positions when no sort mode is set`() {
        val library = libraryOf(
            // No sort set (mangaSort = null); mangaOrder populated by past drag-and-drop.
            category(
                name = "Default",
                mangaOrder = listOf(3L, 1L, 2L),
            ) to listOf(item(1), item(2), item(3)),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(3L, 1L, 2L), result.flatIdsInOrder())
    }

    @Test
    fun `mangaOrder fallback puts unknown items first`() {
        val library = libraryOf(
            category(
                name = "Default",
                mangaOrder = listOf(2L, 1L),
            ) to listOf(item(1), item(2), item(3, title = "Zeta")),
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        // Item 3 not in mangaOrder → sorts first; then mangaOrder positions [2, 1].
        assertEquals(listOf(3L, 2L, 1L), result.flatIdsInOrder())
    }

    // --- library-wide default fallback ---------------------------------------------------------

    @Test
    fun `library-wide default applies when category has no mode and no mangaOrder`() {
        val library = libraryOf(
            category("Default") to listOf(
                item(1, title = "Charlie"),
                item(2, title = "Alpha"),
                item(3, title = "Bravo"),
            ),
        )
        val result = MangaLibrarySort.sort(
            library = library,
            libraryDefaultMode = LibrarySort.Title,
            libraryDefaultAscending = true,
            randomSeed = 0L,
        )
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    @Test
    fun `library-wide default direction is honored`() {
        val library = libraryOf(
            category("Default") to listOf(
                item(1, totalChapters = 100),
                item(2, totalChapters = 10),
                item(3, totalChapters = 50),
            ),
        )
        // Descending TotalChapters via default.
        val result = MangaLibrarySort.sort(
            library = library,
            libraryDefaultMode = LibrarySort.TotalChapters,
            libraryDefaultAscending = false,
            randomSeed = 0L,
        )
        assertEquals(listOf(1L, 3L, 2L), result.flatIdsInOrder())
    }

    // --- Random mode ---------------------------------------------------------------------------

    @Test
    fun `Random mode is deterministic for a given seed`() {
        // Random catValue defaults to mainValue=8 → categoryValue = 'a' + 8*2 = 'q' asc.
        val items = (1L..10L).map { item(it, title = "title-$it") }
        val library = libraryOf(category("Default", sort = 'q') to items)
        val a = MangaLibrarySort.sort(library, LibrarySort.Title, true, randomSeed = 42L)
        val b = MangaLibrarySort.sort(library, LibrarySort.Title, true, randomSeed = 42L)
        assertEquals(a.flatIdsInOrder(), b.flatIdsInOrder())
    }

    @Test
    fun `Random mode produces different orderings for different seeds`() {
        val items = (1L..20L).map { item(it, title = "title-$it") }
        val library = libraryOf(category("Default", sort = 'q') to items)
        val a = MangaLibrarySort.sort(library, LibrarySort.Title, true, randomSeed = 1L)
        val b = MangaLibrarySort.sort(library, LibrarySort.Title, true, randomSeed = 2L)
        // Vanishingly unlikely the two seeds produce identical orderings for 20 items.
        assertTrue(a.flatIdsInOrder() != b.flatIdsInOrder())
    }

    // --- title tiebreaker ----------------------------------------------------------------------

    @Test
    fun `title tiebreaker fires when primary comparator returns 0`() {
        val library = libraryOf(
            category("Default", sort = 'i') to listOf(
                item(1, title = "Charlie", totalChapters = 100),
                item(2, title = "Alpha", totalChapters = 100),
                item(3, title = "Bravo", totalChapters = 100),
            ),
        )
        // All same chapter count → title tiebreaker orders alphabetically.
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    // --- drag-and-drop on large list (regression for O(n²) avoidance) --------------------------

    @Test
    fun `mangaOrder fallback sorts a 500-item category correctly`() {
        // Smoke test for the O(n log n) shape: just confirm the result is correct on a large
        // input. Wall-clock time isn't asserted here but the precomputed position map ensures
        // each comparator call is O(1) instead of legacy's O(n) indexOf.
        val order = (1L..500L).shuffled().toList()
        val items = (1L..500L).map { item(it, title = "title-$it") }
        val library = libraryOf(
            category(name = "Default", mangaOrder = order) to items,
        )
        val result = MangaLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(order, result.flatIdsInOrder())
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun Map<Category, List<LibraryItem.Manga>>.flatIds(): List<Long> =
        values.flatten().mapNotNull { it.libraryManga.manga.id }.sorted()

    private fun Map<Category, List<LibraryItem.Manga>>.flatIdsInOrder(): List<Long> =
        values.flatten().mapNotNull { it.libraryManga.manga.id }

    private fun libraryOf(
        vararg pairs: Pair<Category, List<LibraryItem.Manga>>,
    ): Map<Category, List<LibraryItem.Manga>> = pairs.toMap()

    private fun category(
        name: String,
        id: Int = name.hashCode(),
        order: Int = 0,
        sort: Char? = null,
        mangaOrder: List<Long> = emptyList(),
        isDynamic: Boolean = false,
    ): Category = CategoryImpl().apply {
        this.id = id
        this.name = name
        this.order = order
        this.mangaSort = sort
        this.mangaOrder = mangaOrder.toMutableList()
        this.isDynamic = isDynamic
    }

    private fun item(
        mangaId: Long,
        title: String = "title-$mangaId",
        totalChapters: Int = 0,
        dateAdded: Long = 0L,
        latestUpdate: Long = 0L,
        lastRead: Long = 0L,
        lastFetch: Long = 0L,
        unread: Int = 0,
        mangaCategory: Int = 0,
    ): LibraryItem.Manga {
        val manga = MangaImpl(id = mangaId, source = 100L, url = "url-$mangaId").apply {
            ogTitle = title
            date_added = dateAdded
        }
        return LibraryItem.Manga(
            libraryManga = LibraryManga(
                manga = manga,
                totalChapters = totalChapters,
                latestUpdate = latestUpdate,
                lastRead = lastRead,
                lastFetch = lastFetch,
                unread = unread,
                category = mangaCategory,
            ),
        )
    }
}
