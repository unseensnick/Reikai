package reikai.presentation.browse.catalogue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * Follows the display-mode preference into a catalogue's own state, for whichever content type.
 *
 * Both types owe this: the shared catalogue renders from a state flow, so a display mode held only
 * as a Compose value is written by the toolbar and then never reaches the grid. `changes()` opens
 * with the stored value, so this seeds the state as well as tracking it.
 */
fun Preference<LibraryDisplayMode>.trackDisplayMode(
    scope: CoroutineScope,
    onChange: (LibraryDisplayMode) -> Unit,
): Job = changes().onEach(onChange).launchIn(scope)
