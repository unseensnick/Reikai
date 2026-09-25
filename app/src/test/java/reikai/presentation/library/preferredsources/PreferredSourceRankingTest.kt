package reikai.presentation.library.preferredsources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PreferredSourceRankingTest {

    // X is ranked but no longer installed, so the screen shows only A and B.
    private val ranking = listOf("A", "X", "B")
    private val visible = setOf("A", "B")

    @Test
    fun `moving up steps over a hidden source and leaves it in place`() {
        moveRanked(ranking, "B", visible, step = -1) shouldBe listOf("B", "X", "A")
    }

    @Test
    fun `moving down steps over a hidden source and leaves it in place`() {
        moveRanked(ranking, "A", visible, step = 1) shouldBe listOf("B", "X", "A")
    }

    @Test
    fun `the top visible source cannot move up`() {
        moveRanked(listOf("X", "A", "B"), "A", visible, step = -1) shouldBe listOf("X", "A", "B")
    }

    @Test
    fun `a hidden ranked source is not offered and an unranked one is available`() {
        val sources = listOf(PreferredSourceItem("A", "Alpha", "en"), PreferredSourceItem("C", "Gamma", "en"))

        preferredSourcesState(listOf("X", "A"), sources) shouldBe PreferredSourcesState.Success(
            preferred = listOf(PreferredSourceItem("A", "Alpha", "en")),
            available = listOf(PreferredSourceItem("C", "Gamma", "en")),
        )
    }
}
