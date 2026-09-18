package reikai.data.legacy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.LibrarySort.Direction.Ascending
import tachiyomi.domain.library.model.LibrarySort.Direction.Descending
import tachiyomi.domain.library.model.LibrarySort.Type

/**
 * A Yokai category's sort letter has to come back as that category's own sort. `reversed` is whether the
 * library that drew the letter ran the date and count sorts newest or largest first on an even letter,
 * which the shipped manga library did and the novel library never did.
 */
class LegacyYokaiCategorySortTest {

    @ParameterizedTest(name = "{0} reversed={1}")
    @MethodSource("letters")
    fun `a Yokai sort letter becomes the category's own sort`(
        order: String,
        reversed: Boolean,
        expected: LibrarySort,
    ) {
        LegacyYokaiDbImporter.yokaiCategorySortToFlags(order, reversed) shouldBe
            (expected.flag or CATEGORY_SORT_CUSTOMIZED)
    }

    @ParameterizedTest(name = "\"{0}\"")
    @MethodSource("noOwnSort")
    fun `a manual or blank order follows the global sort`(order: String) {
        LegacyYokaiDbImporter.yokaiCategorySortToFlags(order, reversedDateAndCountSorts = true) shouldBe 0L
    }

    companion object {
        @JvmStatic
        fun letters() = listOf(
            Arguments.of("a", true, LibrarySort(Type.Alphabetical, Ascending)),
            Arguments.of("b", true, LibrarySort(Type.Alphabetical, Descending)),
            Arguments.of("c", true, LibrarySort(Type.LatestChapter, Descending)),
            Arguments.of("e", true, LibrarySort(Type.UnreadCount, Ascending)),
            Arguments.of("f", true, LibrarySort(Type.UnreadCount, Descending)),
            Arguments.of("g", true, LibrarySort(Type.LastRead, Descending)),
            Arguments.of("h", true, LibrarySort(Type.LastRead, Ascending)),
            Arguments.of("i", true, LibrarySort(Type.TotalChapters, Descending)),
            Arguments.of("k", true, LibrarySort(Type.DateAdded, Descending)),
            Arguments.of("m", true, LibrarySort(Type.ChapterFetchDate, Descending)),
            Arguments.of("q", true, LibrarySort(Type.Random, Ascending)),
            Arguments.of("a", false, LibrarySort(Type.Alphabetical, Ascending)),
            Arguments.of("c", false, LibrarySort(Type.LatestChapter, Ascending)),
            Arguments.of("f", false, LibrarySort(Type.UnreadCount, Descending)),
            Arguments.of("g", false, LibrarySort(Type.LastRead, Ascending)),
            Arguments.of("h", false, LibrarySort(Type.LastRead, Descending)),
            Arguments.of("j", false, LibrarySort(Type.TotalChapters, Descending)),
            Arguments.of("k", false, LibrarySort(Type.DateAdded, Ascending)),
            Arguments.of("n", false, LibrarySort(Type.ChapterFetchDate, Descending)),
        )

        @JvmStatic
        fun noOwnSort() = listOf("", "D", "12/7/31", "o", "z")
    }
}
