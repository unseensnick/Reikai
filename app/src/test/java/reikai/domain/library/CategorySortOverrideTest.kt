package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort

class CategorySortOverrideTest {

    private val global = LibrarySort(LibrarySort.Type.LastRead, LibrarySort.Direction.Descending)
    private val own = LibrarySort(LibrarySort.Type.TotalChapters, LibrarySort.Direction.Ascending)

    private fun category(id: Long, flags: Long) = Category(id = id, name = "C$id", order = 0, flags = flags)

    @Test
    fun `a category without the override bit follows the global sort`() {
        // Own-looking flags, but no CUSTOMIZED bit: it should still follow the global sort.
        sortForCategory(category(7, own.type.flag or own.direction.flag), global) shouldBe global
    }

    @Test
    fun `a category with the override bit uses its own sort`() {
        val flags = own.type.flag or own.direction.flag or CATEGORY_SORT_CUSTOMIZED
        sortForCategory(category(7, flags), global) shouldBe own
    }

    @Test
    fun `zero flags (a fresh category) follows the global sort`() {
        sortForCategory(category(7, 0L), global) shouldBe global
    }

    @Test
    fun `the universal Default category ignores an override and follows the global sort`() {
        // Row 0 serves both libraries, so an override stored on it could not mean one thing for manga
        // and another for novels. Stale bits from before that rule must stay ignored.
        val flags = own.type.flag or own.direction.flag or CATEGORY_SORT_CUSTOMIZED
        sortForCategory(category(Category.UNCATEGORIZED_ID, flags), global) shouldBe global
    }

    @Test
    fun `a stale override bit on the Default category does not count as an override`() {
        isSortOverridden(category(Category.UNCATEGORIZED_ID, CATEGORY_SORT_CUSTOMIZED)) shouldBe false
    }

    @Test
    fun `a real category with the override bit counts as overridden`() {
        isSortOverridden(category(7, CATEGORY_SORT_CUSTOMIZED)) shouldBe true
    }

    @Test
    fun `the Default category cannot keep a sort of its own`() {
        canOverrideSort(category(Category.UNCATEGORIZED_ID, 0L)) shouldBe false
    }

    @Test
    fun `a real category can keep a sort of its own`() {
        canOverrideSort(category(7, 0L)) shouldBe true
    }
}
