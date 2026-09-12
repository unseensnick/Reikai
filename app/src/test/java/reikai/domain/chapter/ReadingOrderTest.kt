package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingOrderTest {

    private val shown = listOf("a", "b", "c")

    @Test
    fun `a descending list is walked from its end`() {
        ReadingOrder.of(shown, sortDescending = true) shouldBe listOf("c", "b", "a")
    }

    @Test
    fun `an ascending list is walked as shown`() {
        ReadingOrder.of(shown, sortDescending = false) shouldBe shown
    }

    @Test
    fun `nothing is next when every chapter is read`() {
        ReadingOrder.nextToRead(shown) { true } shouldBe null
    }

    @Test
    fun `the first chapter has nothing before it`() {
        ReadingOrder.before(shown) { it == "a" } shouldBe emptyList()
    }

    @Test
    fun `a pointer the list does not hold marks nothing`() {
        // Guards the caller: without it, takeWhile-style code would mark the whole list read.
        ReadingOrder.before(shown) { it == "z" } shouldBe emptyList()
    }
}
