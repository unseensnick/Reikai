package reikai.data

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** A held table read before its first query lands, which a backup taken on a cold start did. */
class HeldRowsTest {

    @Test
    fun `a read that can wait gets the table, not the empty seed`() = runTest {
        val query = CompletableDeferred<List<String>>()
        val held = HeldRows(flow { emit(query.await()) }, backgroundScope, emptyList())
        val read = async { held.loaded() }
        launch { query.complete(listOf("mark")) }

        read.await() shouldBe listOf("mark")
    }
}
