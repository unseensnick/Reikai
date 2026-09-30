package reikai.presentation.selection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A multi-select's [SelectionState] and the set a screen collects, written together under one lock.
 * A prune runs off the main thread while taps land on it; a prune that reached only the collected set
 * was undone by the next tap, which rebuilt the selection from the state it had never touched.
 */
class SelectionStore<T> {

    private val lock = Any()
    private var state = SelectionState<T>()
    private val published = MutableStateFlow<Set<T>>(emptySet())
    val selection: StateFlow<Set<T>> = published.asStateFlow()

    /** Apply one verb to the current state, and return what it produced. */
    fun update(verb: (SelectionState<T>) -> SelectionState<T>): SelectionState<T> = synchronized(lock) {
        verb(state).also {
            state = it
            published.value = it.selection
        }
    }

    /** Drop what [present] no longer holds, per [EntrySelection.retain]. */
    fun retain(present: Collection<T>) {
        update { if (it.isEmpty) it else EntrySelection.retain(it, present) }
    }
}
