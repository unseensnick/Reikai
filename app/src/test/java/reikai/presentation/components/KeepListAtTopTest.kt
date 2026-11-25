package reikai.presentation.components

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class KeepListAtTopTest {

    /**
     * Only a list at the very top, with a new first row above it, is kept there; a scroll the user has
     * started, or one into the first row, keeps its place.
     */
    @ParameterizedTest(name = "index {0}, offset {1}, shown first {2}")
    @CsvSource(
        "0, 0, yesterday, true",
        "4, 0, yesterday, false",
        "0, 12, yesterday, false",
        "0, 0, today, false",
    )
    fun `a list stays at the top only when a new first row arrives above it`(
        index: Int,
        offset: Int,
        shownFirstKey: String,
        stays: Boolean,
    ) {
        staysAtTop(index, offset, shownFirstKey = shownFirstKey, firstKey = "today") shouldBe stays
    }
}
