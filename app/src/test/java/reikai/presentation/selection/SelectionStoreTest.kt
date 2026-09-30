package reikai.presentation.selection

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SelectionStoreTest {

    /**
     * A prune that reached only the published set left the state it was computed from untouched, so
     * the next tap rebuilt the selection from that state and brought the pruned rows back.
     */
    @Test
    fun `a pruned row stays out when another is picked`() {
        val store = SelectionStore<Long>()
        store.update { EntrySelection.toggle(it, 1L) }
        store.update { EntrySelection.toggle(it, 2L) }

        store.retain(listOf(2L, 3L))
        store.update { EntrySelection.toggle(it, 3L) }

        store.selection.value shouldBe setOf(2L, 3L)
    }

    /** The pruned anchor would otherwise come back with its row and silently widen the next range. */
    @Test
    fun `a prune drops the anchor with its row`() {
        val store = SelectionStore<Long>()
        store.update { EntrySelection.toggle(it, 1L) }
        store.update { EntrySelection.toggle(it, 3L) }

        store.retain(listOf(1L, 2L))
        store.update { EntrySelection.range(it, 5L, listOf(1L, 2L, 3L, 4L, 5L)) }

        store.selection.value shouldBe setOf(1L, 5L)
    }
}
