package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.database.models.NovelCategoryImpl
import eu.kanade.tachiyomi.ui.library.LibrarySort
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.novel.models.Novel

class NovelLibrarySortTest {

    // --- baseline ---------------------------------------------------------------------------

    @Test
    fun `empty library returns empty`() {
        val result = NovelLibrarySort.sort(
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
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(1L), result.flatIds())
    }

    // --- Title mode --------------------------------------------------------------------------

    @Test
    fun `Title mode ascending sorts A to Z`() {
        val library = libraryOf(
            category("Default", sort = 'a') to listOf(
                item(1, title = "Charlie"),
                item(2, title = "Alpha"),
                item(3, title = "Bravo"),
            ),
        )
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    @Test
    fun `Title mode descending sorts Z to A`() {
        val library = libraryOf(
            category("Default", sort = 'b') to listOf(
                item(1, title = "Alpha"),
                item(2, title = "Bravo"),
                item(3, title = "Charlie"),
            ),
        )
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(3L, 2L, 1L), result.flatIdsInOrder())
    }

    @Test
    fun `Title mode honors removeArticles flag`() {
        val library = libraryOf(
            category("Default", sort = 'a') to listOf(
                item(1, title = "The Aardvark"),
                item(2, title = "Banana"),
            ),
        )
        val withArticles = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L, removeArticles = false)
        assertEquals(listOf(2L, 1L), withArticles.flatIdsInOrder())
        val withoutArticles = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L, removeArticles = true)
        assertEquals(listOf(1L, 2L), withoutArticles.flatIdsInOrder())
    }

    // --- TotalChapters / DateAdded / LatestChapter / LastRead / DateFetched ------------------

    @Test
    fun `TotalChapters ascending puts fewer chapters first`() {
        val library = libraryOf(
            category("Default", sort = 'i') to listOf(
                item(1, totalChapters = 100),
                item(2, totalChapters = 10),
                item(3, totalChapters = 50),
            ),
        )
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
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
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
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
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    @Test
    fun `LatestChapter ascending puts oldest update first`() {
        val library = libraryOf(
            category("Default", sort = 'c') to listOf(
                item(1, latestUpdate = 3_000L),
                item(2, latestUpdate = 1_000L),
                item(3, latestUpdate = 2_000L),
            ),
        )
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    // --- Unread mode (special 0-sink behavior) -----------------------------------------------

    @Test
    fun `Unread mode ascending sinks 0-unread items to the bottom`() {
        val library = libraryOf(
            category("Default", sort = 'e') to listOf(
                item(1, unread = 0),
                item(2, unread = 5),
                item(3, unread = 0),
                item(4, unread = 10),
            ),
        )
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        val ids = result.flatIdsInOrder()
        assertEquals(listOf(2L, 4L), ids.take(2))
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
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(3L, 2L, 1L), result.flatIdsInOrder())
    }

    // --- DragAndDrop mode --------------------------------------------------------------------

    @Test
    fun `explicit DragAndDrop on non-dynamic category falls back to alphabetical`() {
        val library = libraryOf(
            category("Default", sort = 'D') to listOf(
                item(1, title = "Charlie"),
                item(2, title = "Alpha"),
                item(3, title = "Bravo"),
            ),
        )
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    @Test
    fun `DragAndDrop on dynamic category sorts by novel's original category order`() {
        val library = libraryOf(
            category(
                name = "BySource",
                sort = 'D',
                isDynamic = true,
            ) to listOf(
                item(1, title = "A", novelCategory = 30),
                item(2, title = "B", novelCategory = 10),
                item(3, title = "C", novelCategory = 20),
            ),
            category(name = "Cat10", id = 10, order = 0) to emptyList(),
            category(name = "Cat20", id = 20, order = 1) to emptyList(),
            category(name = "Cat30", id = 30, order = 2) to emptyList(),
        )
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        val bySourceIds = result.entries
            .first { it.key.name == "BySource" }
            .value.mapNotNull { it.libraryNovel.novel.id }
        assertEquals(listOf(2L, 3L, 1L), bySourceIds)
    }

    // --- novelOrder fallback -----------------------------------------------------------------

    @Test
    fun `novelOrder fallback respects user-defined positions when no sort mode is set`() {
        val library = libraryOf(
            category(
                name = "Default",
                novelOrder = listOf(3L, 1L, 2L),
            ) to listOf(item(1), item(2), item(3)),
        )
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(3L, 1L, 2L), result.flatIdsInOrder())
    }

    @Test
    fun `novelOrder fallback puts unknown items first`() {
        val library = libraryOf(
            category(
                name = "Default",
                novelOrder = listOf(2L, 1L),
            ) to listOf(item(1), item(2), item(3, title = "Zeta")),
        )
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(3L, 2L, 1L), result.flatIdsInOrder())
    }

    // --- library-wide default fallback -------------------------------------------------------

    @Test
    fun `library-wide default applies when category has no mode and no novelOrder`() {
        val library = libraryOf(
            category("Default") to listOf(
                item(1, title = "Charlie"),
                item(2, title = "Alpha"),
                item(3, title = "Bravo"),
            ),
        )
        val result = NovelLibrarySort.sort(
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
        val result = NovelLibrarySort.sort(
            library = library,
            libraryDefaultMode = LibrarySort.TotalChapters,
            libraryDefaultAscending = false,
            randomSeed = 0L,
        )
        assertEquals(listOf(1L, 3L, 2L), result.flatIdsInOrder())
    }

    // --- Random mode -------------------------------------------------------------------------

    @Test
    fun `Random mode is deterministic for a given seed`() {
        val items = (1L..10L).map { item(it, title = "title-$it") }
        val library = libraryOf(category("Default", sort = 'q') to items)
        val a = NovelLibrarySort.sort(library, LibrarySort.Title, true, randomSeed = 42L)
        val b = NovelLibrarySort.sort(library, LibrarySort.Title, true, randomSeed = 42L)
        assertEquals(a.flatIdsInOrder(), b.flatIdsInOrder())
    }

    @Test
    fun `Random mode produces different orderings for different seeds`() {
        val items = (1L..20L).map { item(it, title = "title-$it") }
        val library = libraryOf(category("Default", sort = 'q') to items)
        val a = NovelLibrarySort.sort(library, LibrarySort.Title, true, randomSeed = 1L)
        val b = NovelLibrarySort.sort(library, LibrarySort.Title, true, randomSeed = 2L)
        assertTrue(a.flatIdsInOrder() != b.flatIdsInOrder())
    }

    // --- title tiebreaker --------------------------------------------------------------------

    @Test
    fun `title tiebreaker fires when primary comparator returns 0`() {
        val library = libraryOf(
            category("Default", sort = 'i') to listOf(
                item(1, title = "Charlie", totalChapters = 100),
                item(2, title = "Alpha", totalChapters = 100),
                item(3, title = "Bravo", totalChapters = 100),
            ),
        )
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(listOf(2L, 3L, 1L), result.flatIdsInOrder())
    }

    // --- novelOrder fallback on large list ---------------------------------------------------

    @Test
    fun `novelOrder fallback sorts a 500-item category correctly`() {
        val order = (1L..500L).shuffled().toList()
        val items = (1L..500L).map { item(it, title = "title-$it") }
        val library = libraryOf(
            category(name = "Default", novelOrder = order) to items,
        )
        val result = NovelLibrarySort.sort(library, LibrarySort.Title, true, 0L)
        assertEquals(order, result.flatIdsInOrder())
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun Map<NovelCategory, List<LibraryItem.Novel>>.flatIds(): List<Long> =
        values.flatten().mapNotNull { it.libraryNovel.novel.id }.sorted()

    private fun Map<NovelCategory, List<LibraryItem.Novel>>.flatIdsInOrder(): List<Long> =
        values.flatten().mapNotNull { it.libraryNovel.novel.id }

    private fun libraryOf(
        vararg pairs: Pair<NovelCategory, List<LibraryItem.Novel>>,
    ): Map<NovelCategory, List<LibraryItem.Novel>> = pairs.toMap()

    private fun category(
        name: String,
        id: Int = name.hashCode(),
        order: Int = 0,
        sort: Char? = null,
        novelOrder: List<Long> = emptyList(),
        isDynamic: Boolean = false,
    ): NovelCategory = NovelCategoryImpl().apply {
        this.id = id
        this.name = name
        this.order = order
        this.novelSort = sort
        this.novelOrder = novelOrder.toMutableList()
        this.isDynamic = isDynamic
    }

    private fun item(
        novelId: Long,
        title: String = "title-$novelId",
        totalChapters: Int = 0,
        dateAdded: Long = 0L,
        latestUpdate: Long = 0L,
        lastRead: Long = 0L,
        lastFetch: Long = 0L,
        unread: Int = 0,
        novelCategory: Int = 0,
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
            libraryNovel = LibraryNovel(
                novel = novel,
                totalChapters = totalChapters,
                latestUpdate = latestUpdate,
                lastRead = lastRead,
                lastFetch = lastFetch,
                unread = unread,
                category = novelCategory,
            ),
        )
    }
}
