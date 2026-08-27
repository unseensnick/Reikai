package reikai.presentation.browse.catalogue

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * A catalogue builds one of these per presented row, and rebuilds them whenever the pager is
 * collected afresh, with no scope that ends when a row scrolls away. These pin the property that
 * makes that safe: the view is inert until something collects it.
 */
class MapStateTest {

    @Test
    fun `building a mapped view subscribes to nothing`() {
        val source = MutableStateFlow(1)

        source.mapState { it * 2 }

        source.subscriptionCount.value shouldBe 0
    }

    @Test
    fun `reading the mapped value follows the source`() {
        val source = MutableStateFlow(1)
        val mapped = source.mapState { it * 2 }

        source.value = 5

        mapped.value shouldBe 10
    }

    @Test
    fun `a collector sees every mapped emission`() = runTest {
        val source = MutableStateFlow(1)
        val seen = mutableListOf<Int>()
        val collecting = launch { source.mapState { it * 2 }.collect { seen += it } }
        advanceUntilIdle()

        source.value = 3
        advanceUntilIdle()
        collecting.cancel()

        seen shouldBe listOf(2, 6)
    }

    @Test
    fun `the subscription ends with the collector`() = runTest {
        val source = MutableStateFlow(1)
        val collecting = launch { source.mapState { it * 2 }.collect { } }
        advanceUntilIdle()

        collecting.cancel()
        advanceUntilIdle()

        source.subscriptionCount.value shouldBe 0
    }
}
