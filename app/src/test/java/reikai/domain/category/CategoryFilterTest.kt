package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class CategoryFilterTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("activeCases")
    fun `a filter is active only while enabled with a selection`(
        case: String,
        enabled: Boolean,
        include: Set<Long>,
        exclude: Set<Long>,
        active: Boolean,
    ) {
        categoryFilterActive(enabled, include, exclude) shouldBe active
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matchCases")
    fun `a series passes on an included category and on no excluded one`(
        case: String,
        categories: List<Long>,
        include: Set<Long>,
        exclude: Set<Long>,
        passes: Boolean,
    ) {
        matchesCategoryFilter(categories, include, exclude) shouldBe passes
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateScopeCases")
    fun `a manual update of one category overrides the update filter`(
        case: String,
        categories: List<Long>,
        categoryId: Long,
        include: Set<Long>,
        exclude: Set<Long>,
        covered: Boolean,
    ) {
        isUpdateScope(categories, categoryId, include, exclude) shouldBe covered
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mergeCases")
    fun `a confirm replaces only the ids the picker showed`(
        case: String,
        stored: Set<Long>,
        shown: Set<Long>,
        confirmed: Set<Long>,
        merged: Set<Long>,
    ) {
        mergeCategorySelection(stored, shown, confirmed) shouldBe merged
    }

    @Test
    fun `an empty category list matches no included category, Default included`() {
        matchesCategoryFilter(categories = emptyList(), include = setOf(0L), exclude = emptySet()) shouldBe false
    }

    private companion object {
        @JvmStatic
        fun activeCases() = listOf(
            Arguments.of("disabled with selections", false, setOf(1L), setOf(2L), false),
            Arguments.of("enabled with nothing selected", true, emptySet<Long>(), emptySet<Long>(), false),
            Arguments.of("enabled with an include", true, setOf(1L), emptySet<Long>(), true),
            Arguments.of("enabled with only an exclude", true, emptySet<Long>(), setOf(2L), true),
        )

        @JvmStatic
        fun matchCases() = listOf(
            Arguments.of("no include constraint", listOf(5L), emptySet<Long>(), emptySet<Long>(), true),
            Arguments.of("on an included category", listOf(1L, 3L), setOf(3L), emptySet<Long>(), true),
            Arguments.of("missing every included category", listOf(1L, 2L), setOf(3L), emptySet<Long>(), false),
            Arguments.of("on an excluded category", listOf(1L, 2L), emptySet<Long>(), setOf(2L), false),
            Arguments.of("exclude wins over include", listOf(1L, 2L), setOf(1L), setOf(2L), false),
            Arguments.of("uncategorized against another include", listOf(0L), setOf(1L), emptySet<Long>(), false),
            Arguments.of("uncategorized against Default", listOf(0L), setOf(0L), emptySet<Long>(), true),
        )

        @JvmStatic
        fun updateScopeCases() = listOf(
            Arguments.of("manual category the filter excludes", listOf(2L), 2L, setOf(1L), setOf(2L), true),
            Arguments.of("outside the manual category", listOf(1L), 2L, emptySet<Long>(), emptySet<Long>(), false),
            Arguments.of("no manual category, the filter decides", listOf(1L), -1L, setOf(1L), emptySet<Long>(), true),
        )

        @JvmStatic
        fun mergeCases() = listOf(
            Arguments.of("keeps ids never shown", setOf(1L, 9L), setOf(1L, 2L), setOf(1L), setOf(1L, 9L)),
            Arguments.of("drops a shown id cleared", setOf(1L, 2L), setOf(1L, 2L), setOf(1L), setOf(1L)),
            Arguments.of("adds a newly selected id", setOf(1L), setOf(1L, 2L), setOf(1L, 2L), setOf(1L, 2L)),
            Arguments.of("empty picker", setOf(1L, 2L), emptySet<Long>(), emptySet<Long>(), setOf(1L, 2L)),
        )
    }
}
