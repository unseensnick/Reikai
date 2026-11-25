package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("defaults")
    fun `the default category decides the add or asks`(
        case: String,
        categoryIds: List<Long>,
        default: Int,
        ids: List<Long>?,
    ) {
        resolveDefaultCategoryIds(categoryIds.map(::category), default) shouldBe ids
    }

    private companion object {
        @JvmStatic
        fun defaults() = listOf(
            Arguments.of("a set default applies directly", listOf(3L, 7L), 7, listOf(7L)),
            Arguments.of("none adds uncategorized", listOf(3L), 0, emptyList<Long>()),
            Arguments.of("no categories adds uncategorized", emptyList<Long>(), -1, emptyList<Long>()),
            Arguments.of("always ask with categories present prompts", listOf(3L), -1, null),
            Arguments.of("a default pointing at a deleted category prompts", listOf(3L), 9, null),
        )
    }
}
