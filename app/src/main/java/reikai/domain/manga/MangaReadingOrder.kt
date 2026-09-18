package reikai.domain.manga

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga

/**
 * [manga]'s chapters in the order its reader pages through them, earliest first, under the sort picked
 * for its chapter list. A merged group's list included: the stitch restamps source order onto it, so
 * the default sort still walks the stitch. The reader sorts its own list with this same comparator.
 */
fun List<Chapter>.inReadingOrder(manga: Manga): List<Chapter> =
    sortedWith(getChapterSort(manga, sortDescending = false))
