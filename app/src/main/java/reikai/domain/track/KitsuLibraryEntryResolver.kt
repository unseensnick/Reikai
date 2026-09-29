package reikai.domain.track

import eu.kanade.tachiyomi.data.database.models.Track

/** A Kitsu library entry as the signed-in user reads it: who owns it, who is asking, and its manga if any. */
data class KitsuEntryLookup(val ownerId: String, val viewerId: String?, val mangaId: Long?)

/**
 * Finds a Kitsu track's library entry, healing a row restored from a Yokai backup on the way: Yokai kept
 * the entry id where the manga id belongs and no entry id at all. Only a row with no library id is ever
 * read as an entry id, and only an entry the signed-in user owns heals it, because entry ids are global
 * and a stranger's entry would move the row onto the wrong series. Rule record: kitsu-single-api.md.
 */
class KitsuLibraryEntryResolver(
    private val findInLibrary: suspend (Track) -> Track?,
    private val findEntry: suspend (entryId: Long) -> KitsuEntryLookup?,
    private val rewriteCopies: suspend (entryId: Long, mangaId: Long) -> Unit,
) {

    /** The track's remote state, or null when the user's library holds neither reading of its ids. */
    suspend fun find(track: Track): Track? {
        findInLibrary(track)?.let { return it }
        if (!healEntryId(track)) return null
        return findInLibrary(track)
    }

    /** The entry a write or delete targets, resolved first when the row carries none. */
    suspend fun libraryId(track: Track): Long? {
        if (track.hasLibraryId()) return track.library_id
        return find(track)?.library_id ?: track.library_id
    }

    private suspend fun healEntryId(track: Track): Boolean {
        if (track.hasLibraryId()) return false
        val entryId = track.remote_id
        val entry = findEntry(entryId) ?: return false
        if (entry.ownerId != entry.viewerId) return false
        val mangaId = entry.mangaId ?: return false
        rewriteCopies(entryId, mangaId)
        track.remote_id = mangaId
        track.library_id = entryId
        return true
    }

    private fun Track.hasLibraryId() = (library_id ?: 0L) != 0L
}
