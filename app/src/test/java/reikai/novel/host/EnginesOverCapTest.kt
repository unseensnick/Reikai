package reikai.novel.host

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A search over every plugin left one engine and thread live per plugin; past the cap the oldest close. */
class EnginesOverCapTest {

    @Test
    fun `the live engines over the cap are the least recently used`() {
        enginesOverCap(listOf("a" to 30L, "b" to 10L, "c" to 20L), cap = 1) { it.second }.map { it.first } shouldBe
            listOf("b", "c")
    }

    @Test
    fun `nothing closes while the live engines fit the cap`() {
        enginesOverCap(listOf("a" to 30L, "b" to 10L), cap = 2) { it.second } shouldBe emptyList()
    }
}
