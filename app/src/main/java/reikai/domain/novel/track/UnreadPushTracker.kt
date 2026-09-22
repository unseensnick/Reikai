package reikai.domain.novel.track

import eu.kanade.tachiyomi.data.database.models.Track
import reikai.domain.novel.model.NovelChapter

/**
 * A novel tracker that moves its site back when chapters are marked unread, which no tracker does by
 * default: a site's own progress mark is the user's, and an unread is often a mistake undone.
 */
interface UnreadPushTracker {

    /** [track] after moving the site back for [unread], or null when the tracker's setting is off. */
    suspend fun pushUnread(track: Track, unread: List<NovelChapter>): Track?
}
