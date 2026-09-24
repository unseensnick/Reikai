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
    fun subscribeMangaIdsWithUnread(): Flow<Set<Long>>

    fun subscribeNovelIdsWithUnread(): Flow<Set<Long>>

    /**
     * Emits on every write to this type's chapters or its merge stitch, and once on collection. A
     * signal rather than data: it is what tells a resolved continue-reading row that its target may
     * have been read, which no feed emission can say, since a download tick re-emits one too.
     */
    fun mangaChapterWrites(): Flow<Unit>

    fun novelChapterWrites(): Flow<Unit>
}
