package reikai.presentation.stats

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.stats.StatsCardKind.MOST_READ
import reikai.presentation.stats.StatsCardKind.OVERVIEW
import reikai.presentation.stats.StatsCardKind.PUBLISHING
import reikai.presentation.stats.StatsCardKind.READING
import reikai.presentation.stats.StatsCardKind.SCORES
import reikai.presentation.stats.StatsCardKind.TRACKING

class StatsCardKindTest {

    private val lists = StatsCardKind.entries.filter { it.shelf == StatsShelf.LISTS }

    @Test
    fun `with no saved order the cards keep the default layout`() {
        statsCardLayout(emptyList()) shouldBe StatsCardKind.entries
    }

    @Test
    fun `a saved order puts the charts shelf first with Reading leading it`() {
        val order = listOf(READING, PUBLISHING, TRACKING, SCORES, OVERVIEW).map { it.key }

        statsCardLayout(order) shouldBe listOf(READING, PUBLISHING, TRACKING, SCORES, OVERVIEW) + lists
    }

    @Test
    fun `a card saved between another shelf's cards still sits with its own shelf`() {
        val order = listOf(PUBLISHING, MOST_READ, READING).map { it.key }

        statsCardLayout(order) shouldBe listOf(PUBLISHING, READING, TRACKING, SCORES) + lists + OVERVIEW
    }

    @Test
    fun `reordering the shelves carries each shelf's cards in their order`() {
        val layout = statsCardLayout(listOf(OVERVIEW, READING, PUBLISHING).map { it.key })

        layout.withShelves(listOf(StatsShelf.CHARTS, StatsShelf.GLANCE, StatsShelf.LISTS)) shouldBe
            listOf(READING, PUBLISHING, TRACKING, SCORES, OVERVIEW) + lists
    }

    @Test
    fun `reordering one shelf's cards leaves the other shelves as they were`() {
        StatsCardKind.entries.withShelfCards(StatsShelf.CHARTS, listOf(SCORES, READING, PUBLISHING, TRACKING)) shouldBe
            listOf(OVERVIEW, SCORES, READING, PUBLISHING, TRACKING) + lists
    }

    @Test
    fun `a hidden key that names no card is ignored`() {
        statsCardsByKey(listOf(SCORES.key, "gone")) shouldBe setOf(SCORES)
    }
}
