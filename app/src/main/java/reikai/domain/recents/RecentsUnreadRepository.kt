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
}
