package reikai.data

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** A backup taken just after a write read the copy held before it, so its marks went missing. */
    @Test
    fun `a read that can wait sees a write the held copy has not caught up with`() = runTest {
        val table = MutableStateFlow(listOf("old"))
        val held = HeldRows(table, backgroundScope, emptyList())
        held.loaded()
        table.value = listOf("new")

        held.loaded() shouldBe listOf("new")
    }

    /** A reader warms the held copy through a read that can wait, then reads it on a page turn. */
    @Test
    fun `a read that can wait leaves the held copy filled`() = runTest {
        // The held copy's own query is the slow one, so a fresh read alone would come back first.
        val heldQuery = CompletableDeferred<Unit>()
        var queries = 0
        val table = flow {
            if (queries++ == 0) heldQuery.await()
            emit(listOf("mark"))
        }
        val held = HeldRows(table, backgroundScope, emptyList())
        val read = async { held.loaded() }
        launch { heldQuery.complete(Unit) }
        read.await()

        held.held.value shouldBe listOf("mark")
    }
}
