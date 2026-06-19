package reikai.presentation.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CategorySelectionTest {

    @Test
    fun `toggle adds an unselected category`() {
        CategorySelection.toggle(setOf(1L), 2L) shouldBe setOf(1L, 2L)
    }

    @Test
    fun `toggle removes an already selected category`() {
        CategorySelection.toggle(setOf(1L, 2L), 2L) shouldBe setOf(1L)
    }

    @Test
    fun `selectAll unions the visible ids into the selection`() {
        CategorySelection.selectAll(setOf(1L), listOf(1L, 2L, 3L)) shouldBe setOf(1L, 2L, 3L)
    }

    @Test
    fun `invert keeps the visible ids that were not selected`() {
        CategorySelection.invert(setOf(1L, 3L), listOf(1L, 2L, 3L, 4L)) shouldBe setOf(2L, 4L)
    }

    @Test
    fun `invert of an empty selection selects everything visible`() {
        CategorySelection.invert(emptySet(), listOf(1L, 2L)) shouldBe setOf(1L, 2L)
    }
}
