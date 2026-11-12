package reikai.domain.local

import reikai.domain.library.ContentType

/**
 * Hands the volumes a local series lists from other folders back to those folders' own series once it has left the
 * library for good, with their chapters and reading state, so no folder stays hidden behind a series no one can open.
 */
interface LocalVolumeHandBack {
    suspend fun handBack(type: ContentType, ids: List<Long>)
}
