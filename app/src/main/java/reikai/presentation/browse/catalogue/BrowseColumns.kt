package reikai.presentation.browse.catalogue

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The library's own column counts, which both catalogues follow: one screen serving two content types
 * has one grid, so a per-type column setting would be two answers to one question. Zero means the
 * adaptive width upstream uses.
 */
@Immutable
data class BrowseColumns(val portrait: Int = 0, val landscape: Int = 0) {

    fun gridCells(isLandscape: Boolean): GridCells {
        val columns = if (isLandscape) landscape else portrait
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }
}

/** Follows the column counts into a catalogue's own state, seeding it too, as [trackDisplayMode] does. */
fun LibraryPreferences.trackBrowseColumns(scope: CoroutineScope, onChange: (BrowseColumns) -> Unit): Job =
    combine(portraitColumns.changes(), landscapeColumns.changes(), ::BrowseColumns)
        .onEach(onChange)
        .launchIn(scope)
