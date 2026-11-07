package reikai.presentation.stats

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.stats.StatsCardKind.OVERVIEW
import reikai.presentation.stats.StatsFigureKind.IN_LIBRARY
import reikai.presentation.stats.StatsFigureKind.MEAN_SCORE

class StatsFigureKindTest {

    @Test
    fun `with no saved order the figures keep the strip's order`() {
        statsFigureLayout(emptyList()) shouldBe StatsFigureKind.entries
    }

    @Test
    fun `a saved order puts its figures first`() {
        statsFigureLayout(listOf(MEAN_SCORE.key, IN_LIBRARY.key)).take(2) shouldBe listOf(MEAN_SCORE, IN_LIBRARY)
    }

    @Test
    fun `a hidden key that names no figure is ignored`() {
        statsFiguresByKey(listOf(MEAN_SCORE.key, "gone")) shouldBe setOf(MEAN_SCORE)
    }

    @Test
    fun `hiding every figure hides the overview card`() {
        statsShownCards(StatsCardKind.entries, emptySet(), StatsFigureKind.entries.toSet()) shouldNotContain OVERVIEW
    }

    @Test
    fun `hiding some figures keeps the overview card`() {
        statsShownCards(StatsCardKind.entries, emptySet(), setOf(MEAN_SCORE)) shouldContain OVERVIEW
    }
}
