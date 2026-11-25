package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HeaderSourceTest {

    @ParameterizedTest(name = "{0} sources, chip {1}: {2}")
    @CsvSource(
        // The unified view of a merged group.
        "2, , true",
        // A chosen source of a merged group names that source.
        "2, 7, false",
        "1, , false",
    )
    fun `only the unified view of a merged group names the whole group`(
        sourceCount: Int,
        selectedSource: Long?,
        wholeGroup: Boolean,
    ) {
        headerNamesWholeGroup(sourceCount, selectedSource) shouldBe wholeGroup
    }
}
