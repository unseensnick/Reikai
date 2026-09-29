package reikai.domain.library

import tachiyomi.domain.library.model.LibrarySort

/**
 * Maps a novel category's legacy `flags` onto Mihon's layout. Only the Downloaded and TrackerMean sort
 * values differ (swapped); every other bit passes through. The novel values are literals because the old
 * novel sort type is gone and this runs on every upgrade. See category-schema-unification.md.
 */
fun novelCategoryFlagsToMangaLayout(flags: Long): Long {
    val novelType = flags and SORT_TYPE_MASK
    val mangaType = when (novelType) {
        NOVEL_DOWNLOADED -> LibrarySort.Type.Downloaded.flag
        NOVEL_TRACKER_MEAN -> LibrarySort.Type.TrackerMean.flag
        else -> novelType
    }
    return (flags and SORT_TYPE_MASK.inv()) or mangaType
}

// Mihon's sort-type mask (LibrarySort.Type.mask); the two swapped values live inside it.
private const val SORT_TYPE_MASK = 0b111100L
private const val NOVEL_DOWNLOADED = 0b100000L
private const val NOVEL_TRACKER_MEAN = 0b100100L
