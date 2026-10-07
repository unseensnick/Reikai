package reikai.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test

class RunCatchingCancellableTest {

    @Test
    fun `a cancellation is rethrown rather than reported`() {
        shouldThrow<CancellationException> { runCatchingCancellable { throw CancellationException("gone") } }
    }

    /** What the sites it replaced did not do: they caught Exception, so an Error left their scope. */
    @Test
    fun `an Error is reported as a failure like any other throwable`() {
        val error = NoSuchMethodError("stale extension")

        runCatchingCancellable { throw error }.exceptionOrNull() shouldBe error
    }

    @Test
    fun `a value is reported as a success`() {
        runCatchingCancellable { 1 }.getOrNull() shouldBe 1
    }
}
