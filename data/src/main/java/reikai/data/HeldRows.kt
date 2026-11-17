package reikai.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A table's [rows] held in memory from first use: [held] for a reader that cannot suspend, [empty] until the
 * first read lands, and [loaded] for one that can, which a backup or a listing must use. It reads the table as
 * it stands, so it never takes the empty seed, or a copy a write has not reached yet, for the table. It also
 * waits for [held]'s first read, so a reader can warm the held copy through it before a page turn reads it.
 */
internal class HeldRows<T>(private val rows: Flow<T>, scope: CoroutineScope, empty: T) {
    private val state = MutableStateFlow(empty)
    private val firstRead = CompletableDeferred<Unit>()

    val held: StateFlow<T> = state.asStateFlow()

    init {
        scope.launch {
            rows.collect {
                state.value = it
                firstRead.complete(Unit)
            }
        }
            // Also on a failed query or a scope already ended, so neither leaves every [loaded] waiting.
            .invokeOnCompletion { firstRead.complete(Unit) }
    }

    suspend fun loaded(): T {
        firstRead.await()
        return rows.first()
    }
}
