package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class InFlightLoadsTest {

    private val loads = InFlightLoads<Long>()

    @Test
    fun `a key already loading does not start again`() {
        loads.begin(1L)

        loads.begin(1L) shouldBe false
    }

    @Test
    fun `a key starts again once its load finished`() {
        loads.begin(1L)
        loads.finish(1L)

        loads.begin(1L) shouldBe true
    }

    @Test
    fun `waiting on a running load returns when it finishes`() = runTest {
        loads.begin(1L)
        var waited = false
        val waiter = launch {
            loads.awaitIdle(1L)
            waited = true
        }
        testScheduler.runCurrent()
        val beforeFinish = waited
        loads.finish(1L)
        waiter.join()

        (beforeFinish to waited) shouldBe (false to true)
    }
}
