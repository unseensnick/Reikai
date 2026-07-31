package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import reikai.domain.entry.EntryId
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga

/**
 * Pins [assembleLibrary] to the bucketing, ordering, sort-scope and empty-category rules the manga and
 * novel models apply today, so the engine can render the assembled list knowing nothing visible moved.
 * Fixtures are hand-derived from those rules (the models themselves resolve Injekt at init, so they
 * cannot run here); each rule's origin is cited in LibraryAssembly.kt's KDoc.
 */
class LibraryAssemblyTest {

    private fun item(
        id: Long,
        title: String = "Title $id",
        categories: List<Long> = emptyList(),
        dateAdded: Long = 0,
        novel: Boolean = false,
    ): LibraryItem {
        val manga = Manga.create().copy(id = id, title = title, dateAdded = dateAdded)
        return LibraryItem(
            libraryManga = LibraryManga(
                manga = manga,
                categories = categories,
                totalChapters = 0,
                readCount = 0,
                bookmarkCount = 0,
                latestUpload = 0,
                chapterFetchedAt = 0,
                lastRead = 0,
            ),
            downloadCount = 0,
            unreadCount = 0,
            isLocal = false,
            badges = LibraryItem.Badges(
                downloadCount = 0,
                unreadCount = 0,
                isLocal = false,
                sourceLanguage = "",
            ),
            entryId = if (novel) EntryId.Novel(id) else EntryId.Manga(id),
        )
    }

    private fun category(id: Long, order: Long = id, flags: Long = 0, name: String = "Cat $id") =
        Category(id = id, name = name, order = order, flags = flags)

    private val system = category(0, order = -1, name = "")

    private fun inputs(
        keepEmpty: Boolean = true,
        showHidden: Boolean = false,
        categorySortOrder: Int = 0,
        dropEmptyWhileFiltering: Boolean = false,
        sort: LibrarySort = LibrarySort.default,
        seed: Long = 0,
    ) = LibraryAssemblyInputs(
        globalSort = sort,
        randomSeed = seed,
        showHiddenCategories = showHidden,
        categorySortOrder = categorySortOrder,
        keepEmptyCategories = keepEmpty,
        dropEmptyWhileFiltering = dropEmptyWhileFiltering,
    )

    private val fields = mixedLibraryItemSortFields { -1.0 }

    private fun titlesByCategory(result: List<Pair<Category, List<LibraryItem>>>) =
        result.map { (cat, items) -> cat.id to items.map { it.libraryManga.manga.title } }

    @Test
    fun `an empty category is kept under the manga rule`() {
        val result = assembleLibrary(
            rows = listOf(item(1, categories = listOf(10))),
            categories = listOf(category(10), category(20)),
            inputs = inputs(keepEmpty = true),
            fields = fields,
        )
        result.map { it.first.id } shouldBe listOf(10L, 20L)
    }

    @Test
    fun `an empty category is dropped under the novel rule`() {
        val result = assembleLibrary(
            rows = listOf(item(1, categories = listOf(10))),
            categories = listOf(category(10), category(20)),
            inputs = inputs(keepEmpty = false),
            fields = fields,
        )
        result.map { it.first.id } shouldBe listOf(10L)
    }

    @Test
    fun `the system category appears only when a row is uncategorized`() {
        val result = assembleLibrary(
            rows = listOf(item(1, categories = listOf(10))),
            categories = listOf(system, category(10)),
            inputs = inputs(),
            fields = fields,
        )
        result.map { it.first.id } shouldBe listOf(10L)
    }

    @Test
    fun `an uncategorized row lands in the system bucket whether it carries 0 or nothing`() {
        val result = assembleLibrary(
            rows = listOf(item(1, categories = listOf(0)), item(2, categories = emptyList(), novel = true)),
            categories = listOf(system),
            inputs = inputs(),
            fields = fields,
        )
        titlesByCategory(result) shouldBe listOf(0L to listOf("Title 1", "Title 2"))
    }

    @Test
    fun `a hidden category is dropped unless revealed`() {
        val hidden = category(10, flags = CATEGORY_HIDDEN_MASK)
        val rows = listOf(item(1, categories = listOf(10)))
        assembleLibrary(rows, listOf(hidden), inputs(showHidden = false), fields).size shouldBe 0
        assembleLibrary(rows, listOf(hidden), inputs(showHidden = true), fields).size shouldBe 1
    }

    @Test
    fun `a per-category override sorts only its category`() {
        val overridden = category(
            10,
            flags = (LibrarySort.Type.DateAdded.flag + LibrarySort.Direction.Descending.flag) or
                CATEGORY_SORT_CUSTOMIZED,
        )
        val plain = category(20)
        val result = assembleLibrary(
            rows = listOf(
                item(1, title = "A", categories = listOf(10, 20), dateAdded = 1),
                item(2, title = "B", categories = listOf(10, 20), dateAdded = 2),
            ),
            categories = listOf(overridden, plain),
            inputs = inputs(sort = LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending)),
            fields = fields,
        )
        titlesByCategory(result) shouldBe listOf(10L to listOf("B", "A"), 20L to listOf("A", "B"))
    }

    @Test
    fun `a universal category interleaves both content types under one sort`() {
        val result = assembleLibrary(
            rows = listOf(
                item(1, title = "Cherry", categories = listOf(10)),
                item(2, title = "Apple", categories = listOf(10), novel = true),
                item(3, title = "Banana", categories = listOf(10)),
            ),
            categories = listOf(category(10)),
            inputs = inputs(),
            fields = fields,
        )
        titlesByCategory(result) shouldBe listOf(10L to listOf("Apple", "Banana", "Cherry"))
    }

    @Test
    fun `a manga and a novel sharing a raw id both survive`() {
        val result = assembleLibrary(
            rows = listOf(
                item(7, title = "Manga 7", categories = listOf(10)),
                item(7, title = "Novel 7", categories = listOf(10), novel = true),
            ),
            categories = listOf(category(10)),
            inputs = inputs(),
            fields = fields,
        )
        result.single().second.map { it.entryId } shouldBe listOf(EntryId.Manga(7), EntryId.Novel(7))
    }

    @Test
    fun `random ranks an id-colliding pair by type, not as a tie`() {
        // Same raw id and same title: with a Long-keyed Random both rank identically and the tiebreak
        // ties too, so a stable sort would glue the pair in input order under EVERY seed. The type-aware
        // id must produce some seed where the novel leads despite the manga coming first.
        val rows = listOf(
            item(7, title = "Same", categories = listOf(10)),
            item(7, title = "Same", categories = listOf(10), novel = true),
        )
        val leaders = (0L..8L).map { seed ->
            assembleLibrary(
                rows,
                listOf(category(10)),
                inputs(sort = LibrarySort(LibrarySort.Type.Random, LibrarySort.Direction.Ascending), seed = seed),
                fields,
            ).single().second.first().entryId
        }
        leaders.toSet() shouldBe setOf(EntryId.Manga(7), EntryId.Novel(7))
    }

    @Test
    fun `a category present in both type lists appears once`() {
        val universal = category(10)
        val result = assembleLibrary(
            rows = listOf(item(1, categories = listOf(10))),
            categories = listOf(universal, universal),
            inputs = inputs(),
            fields = fields,
        )
        result.map { it.first.id } shouldBe listOf(10L)
    }

    @Test
    fun `dropEmptyWhileFiltering hides a category the filter emptied`() {
        val result = assembleLibrary(
            rows = listOf(item(1, categories = listOf(10))),
            categories = listOf(category(10), category(20)),
            inputs = inputs(keepEmpty = true, dropEmptyWhileFiltering = true),
            fields = fields,
        )
        result.map { it.first.id } shouldBe listOf(10L)
    }

    @Test
    fun `category order follows the order column then the sort-order pref`() {
        val result = assembleLibrary(
            rows = listOf(item(1, categories = listOf(0))),
            categories = listOf(
                category(20, order = 2, name = "Alpha"),
                category(10, order = 1, name = "Zeta"),
                system,
            ),
            inputs = inputs(categorySortOrder = 1),
            fields = fields,
        )
        result.map { it.first.id } shouldBe listOf(0L, 20L, 10L)
    }
}
