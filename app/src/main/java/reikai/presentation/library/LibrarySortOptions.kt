package reikai.presentation.library

import dev.icerock.moko.resources.StringResource
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.i18n.MR

/**
 * The library sort modes in the Sort tab's order, which is Mihon's. Tracker score only joins with a
 * logged-in tracker, since nothing else could score an entry.
 */
internal fun librarySortTypes(hasTracker: Boolean): List<LibrarySort.Type> = listOfNotNull(
    LibrarySort.Type.Alphabetical,
    LibrarySort.Type.TotalChapters,
    LibrarySort.Type.LastRead,
    LibrarySort.Type.LastUpdate,
    LibrarySort.Type.UnreadCount,
    LibrarySort.Type.LatestChapter,
    LibrarySort.Type.ChapterFetchDate,
    LibrarySort.Type.DateAdded,
    LibrarySort.Type.TrackerMean.takeIf { hasTracker },
    LibrarySort.Type.Downloaded,
    LibrarySort.Type.Random,
)

/** The display label for a library sort mode, reusing Mihon's Sort-tab strings. */
internal fun sortLabelRes(type: LibrarySort.Type): StringResource = when (type) {
    LibrarySort.Type.Alphabetical -> MR.strings.action_sort_alpha
    LibrarySort.Type.TotalChapters -> MR.strings.action_sort_total
    LibrarySort.Type.LastRead -> MR.strings.action_sort_last_read
    LibrarySort.Type.LastUpdate -> MR.strings.action_sort_last_manga_update
    LibrarySort.Type.UnreadCount -> MR.strings.action_sort_unread_count
    LibrarySort.Type.LatestChapter -> MR.strings.action_sort_latest_chapter
    LibrarySort.Type.ChapterFetchDate -> MR.strings.action_sort_chapter_fetch_date
    LibrarySort.Type.DateAdded -> MR.strings.action_sort_date_added
    LibrarySort.Type.TrackerMean -> MR.strings.action_sort_tracker_score
    LibrarySort.Type.Downloaded -> MR.strings.action_sort_downloaded
    LibrarySort.Type.Random -> MR.strings.action_sort_random
}
