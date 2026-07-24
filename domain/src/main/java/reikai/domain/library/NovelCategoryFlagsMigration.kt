package reikai.domain.library

import tachiyomi.domain.library.model.LibrarySort

/**
 * One-time translation of a novel category's `flags` from the legacy novel bit layout to Mihon's manga
 * layout, used when novel categories fold into the shared `categories` table.
 *
 * The two layouts differ in exactly one place: the novel side stored the Downloaded and TrackerMean sort
 * types on swapped values (novel Downloaded `0b100000`, TrackerMean `0b100100`), the mirror of Mihon's
 * ([LibrarySort.Type.Downloaded] `0b100100`, [LibrarySort.Type.TrackerMean] `0b100000`). Every other bit
 * is identical across the two: the [CATEGORY_SORT_CUSTOMIZED] override bit, the direction bit, and the
 * hidden bit. So only those two type values are remapped and all other bits pass through unchanged.
 *
 * The novel source values are literals, not references to `NovelLibrarySort`, because that type is
 * retired once the novel library reads through the shared manga layout. This translation must keep
 * working for every device upgrading afterwards, so it cannot depend on the retired class.
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
