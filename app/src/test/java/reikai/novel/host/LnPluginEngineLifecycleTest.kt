package reikai.novel.host

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JVM has no QuickJS native library, so these pin the two rules the host's engine lifecycle
 * runs through, over stand-ins: what a close does for a cancelled caller, and what a load's
 * timeout counts. HeadlessJsIntegrationTest drives the real engines on a device.
 */
class LnPluginEngineLifecycleTest {

    @Test
    fun `a caller cancelled before it closes its engine still closes it and retires its thread`() = runTest {
        val closed = AtomicBoolean()
        val executor = Executors.newSingleThreadExecutor()

        launch {
            // A timed-out read reaches its close from a finally, already cancelled.
            cancel()
            retireEngine(Closeable { closed.set(true) }, executor.asCoroutineDispatcher(), executor)
        }.join()

        listOf(closed.get(), executor.isShutdown) shouldBe listOf(true, true)
    }

    @Test
    fun `a read queued behind the permits gets its whole timeout once it holds one`() = runTest {
        val permits = Semaphore(1)
        val first = async { permits.withPermitAndTimeout(30_000L, "first") { delay(20_000L).let { "first" } } }

        val queued = permits.withPermitAndTimeout(30_000L, "queued") { delay(20_000L).let { "queued" } }

        listOf(first.await(), queued) shouldBe listOf("first", "queued")
    }
}
