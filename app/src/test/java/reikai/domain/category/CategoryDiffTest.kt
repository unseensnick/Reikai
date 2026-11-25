package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class CategoryDiffTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("selections")
    fun `common is the intersection, mix is the rest of the union`(
        case: String,
        entries: List<Set<Long>>,
        diff: CategoryDiff,
    ) {
        categoryDiff(entries) shouldBe diff
    }

    private companion object {
        @JvmStatic
        fun selections() = listOf(
            Arguments.of(
                "overlapping entries",
                listOf(setOf(1L, 2L, 3L), setOf(2L, 3L, 4L)),
                CategoryDiff(common = setOf(2L, 3L), mix = setOf(1L, 4L)),
            ),
            Arguments.of(
                "the same categories on every entry",
                listOf(setOf(1L, 2L), setOf(1L, 2L)),
                CategoryDiff(common = setOf(1L, 2L), mix = emptySet()),
            ),
            Arguments.of(
                "no category shared",
                listOf(setOf(1L), setOf(2L)),
                CategoryDiff(common = emptySet(), mix = setOf(1L, 2L)),
            ),
            Arguments.of(
                "a single entry",
                listOf(setOf(1L, 2L, 3L)),
                CategoryDiff(common = setOf(1L, 2L, 3L), mix = emptySet()),
            ),
            Arguments.of("an empty selection", emptyList<Set<Long>>(), CategoryDiff(emptySet(), emptySet())),
        )
    }
}
