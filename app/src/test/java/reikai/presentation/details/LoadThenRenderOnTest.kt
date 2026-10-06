package reikai.presentation.details

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * A tick (a download finishing, the queue moving) re-renders what was last loaded; only an input change
 * loads again. Loading a merged series subscribes every member's chapter rows.
 */
class LoadThenRenderOnTest {

    private val inputs = MutableStateFlow(1)
    private val ticks = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    private var loads = 0
    private val renders = mutableListOf<Int>()

    private fun TestScope.start() {
        backgroundScope.launch {
            inputs.loadThenRenderOn(ticks) { input ->
                loads++
                flowOf(input * 10)
            }
                .collect { renders += it }
        }
        runCurrent()
    }

    private fun TestScope.tick(times: Int) {
        repeat(times) {
            ticks.tryEmit(Unit)
            runCurrent()
        }
    }

    @Test
    fun `ticks re-render the last load without loading again`() = runTest {
        start()
        tick(5)
        loads shouldBe 1
    }

    @Test
    fun `every tick reaches the render`() = runTest {
        start()
        tick(5)
        renders shouldBe List(6) { 10 }
    }

    @Test
    fun `an input change loads again`() = runTest {
        start()
        inputs.value = 2
        runCurrent()
        renders.last() shouldBe 20
    }
}
