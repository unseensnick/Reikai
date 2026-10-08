package reikai.data.notification

import eu.kanade.tachiyomi.data.notification.Notifications

/**
 * Where one novel's new-chapters notice sits in the shade, for the updater that posts it and every
 * path that dismisses it. Android keys a notification by (tag, id) and manga ids share the number
 * space, so a novel's notice carries [TAG]: untagged, one type's entry replaced the other's.
 */
object NovelNewChaptersNotice {
    const val TAG = "novel_new_chapters"

    /** The group summary's id, which a dismiss takes to clear the summary with its last child. */
    const val SUMMARY_ID = Notifications.ID_NOVEL_LIBRARY_RESULT

    fun id(novelId: Long): Int = novelId.hashCode()
}
