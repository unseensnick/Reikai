package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort

class ReikaiCategoryHiddenTest {

    @Test
    fun `a category with the hidden bit clear is not hidden`() {
        category(flags = 0L).isHidden shouldBe false
    }

    @Test
    fun `a category with the hidden bit set is hidden`() {
        category(flags = CATEGORY_HIDDEN_MASK).isHidden shouldBe true
    }

    @Test
    fun `flagsWithHidden true sets the bit`() {
        category(flags = 0L).flagsWithHidden(true) shouldBe CATEGORY_HIDDEN_MASK
    }

    @Test
    fun `flagsWithHidden false clears the bit`() {
        category(flags = CATEGORY_HIDDEN_MASK).flagsWithHidden(false) shouldBe 0L
    }

    @Test
    fun `setting hidden is idempotent`() {
        val once = category(flags = 0L).flagsWithHidden(true)
        category(flags = once).flagsWithHidden(true) shouldBe once
    }

    @Test
    fun `toggling hidden leaves the sort and direction bits untouched`() {
        val sortFlags = LibrarySort(LibrarySort.Type.LastRead, LibrarySort.Direction.Ascending).flag
        val hidden = category(flags = sortFlags).flagsWithHidden(true)
        category(flags = hidden).flagsWithHidden(false) shouldBe sortFlags
    }

    private fun category(flags: Long): Category =
        Category(id = 1L, name = "Test", order = 0L, flags = flags)
}
