package reikai.presentation.migrate.flow

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LatestPickGateTest {

    private val gate = LatestPickGate<Long>()

    @Test
    fun `a later pick for the same key supersedes one still in flight`() {
        val first = gate.begin(1L, "a")!!
        gate.begin(1L, "b")

        gate.isCurrent(first) shouldBe false
    }

    @Test
    fun `the same pick again while it is in flight is refused`() {
        gate.begin(1L, "a")

        gate.begin(1L, "a").shouldBeNull()
    }

    @Test
    fun `a pick for another key leaves the first current`() {
        val first = gate.begin(1L, "a")!!
        gate.begin(2L, "b")

        gate.isCurrent(first) shouldBe true
    }

    @Test
    fun `the same pick made again once the first ended is a new pick`() {
        gate.end(gate.begin(1L, "a")!!)

        gate.begin(1L, "a").shouldNotBeNull()
    }

    @Test
    fun `a superseded pick ending leaves the later one current`() {
        val first = gate.begin(1L, "a")!!
        val later = gate.begin(1L, "b")!!

        gate.end(first)

        gate.isCurrent(later) shouldBe true
    }
}
