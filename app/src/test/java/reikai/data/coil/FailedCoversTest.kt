package reikai.data.coil

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The load-time half of a merged series' cover fallback: candidates are tried in order and the first that
 * loads is drawn, and one that already failed is tried last. Candidates here are plain strings standing
 * for cover models; [dead] lists the ones whose load fails, the way an empty or HTML-answering cover does.
 */
class FailedCoversTest {

    private val tried = mutableListOf<String>()

    private suspend fun FailedCovers.draw(candidates: List<String>, dead: Set<String>): String? =
        firstLoaded(candidates, isLoaded = { it != null }) { candidate ->
            tried += candidate as String
            candidate.takeIf { it !in dead }
        }

    @Test
    fun `the first candidate that loads is drawn`() = runTest {
        FailedCovers().draw(listOf("lead", "second"), dead = emptySet()) shouldBe "lead"
    }

    @Test
    fun `a lead that fails to load falls back to the next member`() = runTest {
        FailedCovers().draw(listOf("lead", "second", "third"), dead = setOf("lead")) shouldBe "second"
    }

    @Test
    fun `nothing after the cover drawn is loaded`() = runTest {
        FailedCovers().draw(listOf("lead", "second", "third"), dead = setOf("lead"))

        tried shouldContainExactly listOf("lead", "second")
    }

    @Test
    fun `a cover that failed is tried last the next time`() = runTest {
        val covers = FailedCovers()
        covers.draw(listOf("lead", "second"), dead = setOf("lead"))
        tried.clear()

        covers.draw(listOf("lead", "second"), dead = setOf("lead"))

        tried shouldContainExactly listOf("second")
    }

    @Test
    fun `when every cover failed all are tried again in order`() = runTest {
        val covers = FailedCovers()
        covers.draw(listOf("lead", "second"), dead = setOf("lead", "second"))
        tried.clear()

        covers.draw(listOf("lead", "second"), dead = emptySet()) shouldBe "lead"
    }

    @Test
    fun `a cover that loads again moves back ahead of one still failing`() = runTest {
        val covers = FailedCovers()
        covers.draw(listOf("second", "lead"), dead = setOf("second", "lead"))
        covers.draw(listOf("second", "lead"), dead = setOf("second"))
        tried.clear()

        covers.draw(listOf("second", "lead"), dead = emptySet())

        tried shouldContainExactly listOf("lead")
    }
}
