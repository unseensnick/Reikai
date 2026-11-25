package reikai.presentation.widget

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations

/**
 * What Mihon's manga cover widgets draw beyond the raw feed, by the combined widget's rules. Merge
 * groups and Edit info live in the app module, which binds this, so Mihon's widgets reach them here.
 */
interface MangaUpdatesPerSeries {
    /**
     * [updates] as the cover widgets draw them: one row per series, a merged series counting once, under
     * each series' Edit info. It emits again as a group or an edit changes, and the widgets' refresh driver
     * watches it, since a widget whose session has closed redraws only when the driver asks.
     */
    fun drawn(updates: Flow<List<UpdatesWithRelations>>): Flow<List<UpdatesWithRelations>>

    /** The library's merging switch, which the feed reads to hide a merged series with any hidden member. */
    val seriesMerging: Flow<Boolean>
}
