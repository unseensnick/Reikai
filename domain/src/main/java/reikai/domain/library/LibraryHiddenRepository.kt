package reikai.domain.library

import kotlinx.coroutines.flow.Flow

/**
 * The entries hidden from the library view, by raw id per content type, since manga and novel ids
 * collide. The Updates feed and badge leave them out too; updates, notifications, History and Statistics
 * still read them. Write through HideSeries, which reaches every library member of a merged series.
 */
interface LibraryHiddenRepository {

    fun subscribe(type: ContentType): Flow<Set<Long>>

    suspend fun isHidden(type: ContentType, id: Long): Boolean

    suspend fun hide(type: ContentType, ids: List<Long>)

    suspend fun unhide(type: ContentType, ids: List<Long>)
}
