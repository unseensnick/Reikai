package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort

class CategorySortOverrideTest {

    private val global = LibrarySort(LibrarySort.Type.LastRead, LibrarySort.Direction.Descending)
    private val own = LibrarySort(LibrarySort.Type.TotalChapters, LibrarySort.Direction.Ascending)

    @Test
    fun `a category without the override bit follows the global sort`() {
        // Own-looking flags, but no CUSTOMIZED bit: it should still follow the global sort.
        val flags = own.type.flag or own.direction.flag
        sortForCategory(flags, global) shouldBe global
    }

    @Test
    fun `a category with the override bit uses its own sort`() {
        val flags = own.type.flag or own.direction.flag or CATEGORY_SORT_CUSTOMIZED
        sortForCategory(flags, global) shouldBe own
    }

    @Test
    fun `zero flags (a fresh category) follows the global sort`() {
        sortForCategory(0L, global) shouldBe global
    }

    @Test
    fun `the universal Default category ignores an override and follows the global sort`() {
        // Row 0 serves both libraries, so an override stored on it could not mean one thing for manga
        // and another for novels. Stale bits from before that rule must stay ignored.
        val default = Category(
            id = Category.UNCATEGORIZED_ID,
            name = "",
            order = 0,
            flags = own.type.flag or own.direction.flag or CATEGORY_SORT_CUSTOMIZED,
        )
        sortForCategory(default, global) shouldBe global
    }

    @Test
    fun `a real category with the override bit still uses its own sort`() {
        val category = Category(
            id = 7,
            name = "Reading",
            order = 0,
            flags = own.type.flag or own.direction.flag or CATEGORY_SORT_CUSTOMIZED,
        )
        sortForCategory(category, global) shouldBe own
    }
}
