package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.core.common.preference.TriState

class IntervalFilterTest {

    @ParameterizedTest
    @EnumSource(TriState::class)
    fun `the interval filter applies while series outside their release period are skipped`(filter: TriState) {
        effectiveIntervalFilter(skipsOutsideReleasePeriod = true, filter = filter) shouldBe filter
    }

    @ParameterizedTest
    @EnumSource(TriState::class)
    fun `the interval filter is off when release periods are not used`(filter: TriState) {
        effectiveIntervalFilter(skipsOutsideReleasePeriod = false, filter = filter) shouldBe TriState.DISABLED
    }
}
