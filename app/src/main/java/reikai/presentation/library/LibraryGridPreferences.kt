package reikai.presentation.library

import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import kotlinx.coroutines.CoroutineScope
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences

/** The grid shape as Compose state: library-wide, not per content type, wherever a library list draws. */
fun LibraryPreferences.gridDisplayMode(scope: CoroutineScope): PreferenceMutableState<LibraryDisplayMode> =
    displayMode.asState(scope)

fun LibraryPreferences.gridColumns(scope: CoroutineScope, isLandscape: Boolean): PreferenceMutableState<Int> =
    (if (isLandscape) landscapeColumns else portraitColumns).asState(scope)
