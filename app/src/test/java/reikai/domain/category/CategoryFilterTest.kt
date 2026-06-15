package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CategoryFilterTest {

    @Test
    fun `filter is inactive when disabled even with selections`() {
        categoryFilterActive(enabled = false, include = setOf(1L), exclude = setOf(2L)) shouldBe false
    }

    @Test
    fun `filter is inactive when enabled but nothing is selected`() {
        categoryFilterActive(enabled = true, include = emptySet(), exclude = emptySet()) shouldBe false
    }

    @Test
    fun `filter is active when enabled with an include selection`() {
        categoryFilterActive(enabled = true, include = setOf(1L), exclude = emptySet()) shouldBe true
    }

    @Test
    fun `filter is active when enabled with only an exclude selection`() {
        categoryFilterActive(enabled = true, include = emptySet(), exclude = setOf(2L)) shouldBe true
    }

    @Test
    fun `empty include means no include constraint`() {
        matchesCategoryFilter(categories = listOf(5L), include = emptySet(), exclude = emptySet()) shouldBe true
    }

    @Test
    fun `series on an included category passes`() {
        matchesCategoryFilter(categories = listOf(1L, 3L), include = setOf(3L), exclude = emptySet()) shouldBe true
    }

    @Test
    fun `series missing every included category is filtered out`() {
        matchesCategoryFilter(categories = listOf(1L, 2L), include = setOf(3L), exclude = emptySet()) shouldBe false
    }

    @Test
    fun `series on an excluded category is filtered out`() {
        matchesCategoryFilter(categories = listOf(1L, 2L), include = emptySet(), exclude = setOf(2L)) shouldBe false
    }

    @Test
    fun `exclude wins over include when a series is on both`() {
        matchesCategoryFilter(categories = listOf(1L, 2L), include = setOf(1L), exclude = setOf(2L)) shouldBe false
    }

    @Test
    fun `uncategorized series passes when only an include is set`() {
        matchesCategoryFilter(categories = emptyList(), include = setOf(1L), exclude = emptySet()) shouldBe false
    }

    @Test
    fun `uncategorized series can be targeted by the synthetic uncategorized id`() {
        matchesCategoryFilter(categories = listOf(0L), include = setOf(0L), exclude = emptySet()) shouldBe true
    }
}
