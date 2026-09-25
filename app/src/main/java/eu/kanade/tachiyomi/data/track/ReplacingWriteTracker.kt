package eu.kanade.tachiyomi.data.track

/**
 * A tracker whose every write replaces the user's whole list entry rather than merging into it, so
 * binding an entry already on their list clears whatever the write does not carry, such as labels
 * and notes. The tracking sheet asks before binding one, for both content types.
 */
interface ReplacingWriteTracker
