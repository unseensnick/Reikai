package reikai.presentation.stats

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class StatsReloadTest {

    // suspendCoroutine ignores cancellation, as a blocking query does: the older load runs to its end.
    @Test
    fun `an older load finishing after a newer one does not replace it`() = runTest {
        val older = CompletableDeferred<String>()
        val newer = CompletableDeferred<String>()
        val loads = ArrayDeque(listOf(older, newer))
        val reload = reloadOn(this) { loads.removeFirst().uncancellably() }

        reload.reload()
        advanceUntilIdle()
        reload.reload()
        advanceUntilIdle()
        newer.complete("newer")
        advanceUntilIdle()
        older.complete("older")
        advanceUntilIdle()

        reload.value.value shouldBe "newer"
    }

    private suspend fun CompletableDeferred<String>.uncancellably(): String = suspendCoroutine { done ->
        invokeOnCompletion { done.resume(getCompleted()) }
    }

    private fun reloadOn(scope: TestScope, load: suspend () -> String): StatsReload<String> =
        StatsReload(scope, load, loadOn = StandardTestDispatcher(scope.testScheduler))
}
