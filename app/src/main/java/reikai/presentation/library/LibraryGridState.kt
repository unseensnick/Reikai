package reikai.presentation.library

import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import kotlinx.coroutines.CoroutineScope
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The grid shape as Compose state: library-wide, not per content type, wherever a library list draws. Each
 * state is built once per owner, since building one starts a collector that lasts as long as [scope].
 */
class LibraryGridState(preferences: LibraryPreferences, scope: CoroutineScope) {
    val displayMode: PreferenceMutableState<LibraryDisplayMode> by lazy { preferences.displayMode.asState(scope) }
    private val portrait by lazy { preferences.portraitColumns.asState(scope) }
    private val landscape by lazy { preferences.landscapeColumns.asState(scope) }

    fun columns(isLandscape: Boolean): PreferenceMutableState<Int> = if (isLandscape) landscape else portrait
}
