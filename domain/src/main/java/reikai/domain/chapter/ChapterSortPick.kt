package reikai.domain.chapter

/**
 * What a tap on the chapter sort page does to the direction: the mode already shown flips, and a new mode
 * starts ascending. Manga's `SetMangaChapterFlags` and the novel `SetNovelChapterFlags` both answer the
 * sort page through this, so the two lists cannot disagree on it.
 */
object ChapterSortPick {
    fun descendingAfter(shownSorting: Long, shownDescending: Boolean, picked: Long): Boolean =
        picked == shownSorting && !shownDescending
}
