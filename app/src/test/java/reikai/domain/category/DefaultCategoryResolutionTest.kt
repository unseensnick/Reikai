package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

/**
 * Covers the shared default-category decision tree used by both library adders and the
 * bulk-favorite engine: a set default applies directly, "none" (0) and no-categories add
 * uncategorized, and anything else prompts.
 */
class DefaultCategoryResolutionTest {

    private fun category(id: Long) = Category(
        id = id,
        name = "cat$id",
        order = id,
        flags = 0L,
    )

    @Test
    fun `a set default category applies directly`() {
        resolveDefaultCategoryIds(listOf(category(3), category(7)), 7) shouldBe listOf(7L)
    }

    @Test
    fun `default none adds uncategorized`() {
        resolveDefaultCategoryIds(listOf(category(3)), 0) shouldBe emptyList()
    }

    @Test
    fun `no categories adds uncategorized regardless of the preference`() {
        resolveDefaultCategoryIds(emptyList(), -1) shouldBe emptyList()
    }

    @Test
    fun `always-ask with categories present prompts`() {
        resolveDefaultCategoryIds(listOf(category(3)), -1) shouldBe null
    }

    @Test
    fun `a default pointing at a deleted category prompts`() {
        resolveDefaultCategoryIds(listOf(category(3)), 9) shouldBe null
    }
}
