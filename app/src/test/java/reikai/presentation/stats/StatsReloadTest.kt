package reikai.presentation.stats

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StatsReloadTest {

    private class Load(val result: CompletableDeferred<String> = CompletableDeferred()) {
        val started = CompletableDeferred<Unit>()
        val settled = CompletableDeferred<Unit>()
    }

    @Test
    fun `an older load finishing after a newer one does not replace it`() = runTest {
        val older = Load()
        val newer = Load()
        val loads = ConcurrentLinkedQueue(listOf(older, newer))
        val reload = StatsReload(backgroundScope) {
            val load = loads.remove()
            load.started.complete(Unit)
            try {
                load.result.await()
            } finally {
                load.settled.complete(Unit)
            }
        }

        // The loads run on IO, so every wait here is on real time.
        val landed = withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                reload.reload()
                older.started.await()
                reload.reload()
                newer.started.await()
                newer.result.complete("newer")
                reload.value.first { it == "newer" }
                older.result.complete("older")
                older.settled.await()
            }
            withTimeoutOrNull(500.milliseconds) { reload.value.first { it == "older" } }
        }

        landed shouldBe null
    }
}
