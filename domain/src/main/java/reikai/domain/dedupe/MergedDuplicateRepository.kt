package reikai.domain.dedupe

import reikai.domain.library.ContentType

/** A copy of an entry the upgrade's dedupe merged away, and the entry it merged into. */
data class MergedDuplicate(val contentType: ContentType, val discardedId: Long, val survivorId: Long)

/**
 * The record 50.sqm and 51.sqm leave of the duplicates they merged, for the state the database does not
 * hold (custom cover files, the pre-group merge prefs), which is still keyed by the discarded id.
 */
interface MergedDuplicateRepository {

    suspend fun getAll(): List<MergedDuplicate>

    suspend fun clear()
}
