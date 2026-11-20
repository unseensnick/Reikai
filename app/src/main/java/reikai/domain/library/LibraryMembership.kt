package reikai.domain.library

import reikai.domain.entry.CoverColors
import reikai.domain.entry.EntryId
import reikai.domain.entry.signedKey
import reikai.domain.track.source.SourceTrackerDispatcher

/**
 * What an entry joining or leaving the library sets off, on every path and for both types: its cover colour
 * moves to the store [inLibrary] names, since a colour moves otherwise only on a cover load that may never
 * come, and the source's own tracker hears of it. A migration passes [tellSource] false because it tells
 * the tracker through [SourceTrackerDispatcher.migrated], which the user can turn off.
 */
fun SourceTrackerDispatcher.libraryMembershipChanged(entry: EntryId, inLibrary: Boolean, tellSource: Boolean = true) {
    CoverColors.refile(entry.signedKey(), inLibrary)
    if (tellSource) favoriteChanged(entry, inLibrary)
}
