package reikai.data.track

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TrackerRefreshTargetsTest {

    @Test
    fun `every member refreshes when a member's span is itself`() = runTest {
        refreshTargets(listOf(1L, 2L), mapOf(1L to 7L, 2L to 7L)) { listOf(it) } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `a member already covered by an earlier member's span is skipped`() = runTest {
        refreshTargets(listOf(1L, 2L), mapOf(1L to 7L, 2L to 7L)) { listOf(1L, 2L) } shouldBe listOf(1L)
    }

    @Test
    fun `an ungrouped entry costs no span lookup`() = runTest {
        val asked = mutableListOf<Long>()

        refreshTargets(listOf(3L, 1L), mapOf(1L to 7L)) {
            asked += it
            listOf(it)
        }

        asked shouldBe listOf(1L)
    }

    @Test
    fun `an ungrouped entry is kept`() = runTest {
        refreshTargets(listOf(3L, 1L), mapOf(1L to 7L)) { listOf(it) } shouldBe listOf(3L, 1L)
    }

    @Test
    fun `order decides the survivor`() = runTest {
        refreshTargets(listOf(2L, 1L), mapOf(1L to 7L, 2L to 7L)) { listOf(1L, 2L) } shouldBe listOf(2L)
    }
}
