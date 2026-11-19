package reikai.presentation.notes

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A novel's or a local volume's notes page, twin of upstream's `MangaNotesViewModel`, pinned by NotesRestoreTest
 * and NotesSaveConformanceTest. The notes are read when the page opens, never carried by its screen: Android
 * rebuilds a screen from its saved arguments, so a carried copy comes back stale and the next edit saves over
 * newer notes. [load] gives what the page heads with and the notes, or null when they no longer exist ([missing]).
 */
class NotesEditor<S : Any>(
    scope: CoroutineScope,
    private val appScope: CoroutineScope,
    load: suspend () -> Pair<S, String>?,
    private val write: suspend (String) -> Unit,
) {

    val state: StateFlow<Loaded<S>?>
        field = MutableStateFlow<Loaded<S>?>(null)

    val missing: StateFlow<Boolean>
        field = MutableStateFlow(false)

    init {
        scope.launch {
            val (subject, notes) = load() ?: run {
                missing.value = true
                return@launch
            }
            state.value = Loaded(subject, notes)
        }
    }

    fun update(content: String) {
        if (content == state.value?.notes) return
        state.update { it?.copy(notes = content) }
        // On Main so the debounced saves and the one on leaving are written in order, as manga's are
        appScope.launch(Dispatchers.Main.immediate) { write(content) }
    }

    @Immutable
    data class Loaded<S>(val subject: S, val notes: String)
}
