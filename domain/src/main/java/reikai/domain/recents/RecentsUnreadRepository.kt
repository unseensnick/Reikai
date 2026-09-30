package reikai.domain.recents

import kotlinx.coroutines.flow.Flow

/**
 * Which entries of each content type still have an unread chapter, for the recents surface's
 * "show read" filter. One set per emission rather than a question asked per row: the feed re-emits
 * on every library change, and `RecentsProvider.targetChapter` is resolved per rendered row on
 * purpose, so filtering the list cannot pay that cost per row as well.
 *
 * Ids are each type's own row ids, wrapped into an `EntryId` by the adapter that asked.
 */
interface RecentsUnreadRepository {
    /**
     * Answered the way a tap on the row resolves: a library member of a stitched merge group by its
     * group, with excluded scanlators left out. [mergingEnabled] is the series-merging switch, and with
     * it off every entry answers for itself. The rule is written out in recentsUnread.sq.
     */
    fun subscribeMangaIdsWithUnread(mergingEnabled: Boolean): Flow<Set<Long>>

    fun subscribeNovelIdsWithUnread(mergingEnabled: Boolean): Flow<Set<Long>>

    /**
     * Emits on every write to a table this type's continue-reading target is resolved from (listed in
     * recentsUnread.sq), and once on collection. A signal rather than data: it is what tells a resolved
     * row that its target may have moved, which no feed emission can say, since a download tick
     * re-emits one too. The preferences a target reads are the provider's to add.
     */
    fun mangaTargetWrites(): Flow<Unit>

    fun novelTargetWrites(): Flow<Unit>
}
