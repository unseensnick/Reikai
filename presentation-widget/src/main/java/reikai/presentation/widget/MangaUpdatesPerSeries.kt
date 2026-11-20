package reikai.presentation.widget

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations

/**
 * What Mihon's manga cover widgets draw beyond the raw feed, by the combined widget's rules. Merge
 * groups and Edit info live in the app module, which binds this, so Mihon's widgets reach them here.
 */
interface MangaUpdatesPerSeries {
    /** One row per series, a merged series counting once. */
    suspend fun onePerSeries(updates: List<UpdatesWithRelations>): List<UpdatesWithRelations>

    /** [updates] under each series' Edit info overrides, kept current as they are edited. */
    fun withCustomInfo(updates: Flow<List<UpdatesWithRelations>>): Flow<List<UpdatesWithRelations>>

    /** The library's merging switch, which the feed reads to hide a merged series with any hidden member. */
    val seriesMerging: Flow<Boolean>
}
