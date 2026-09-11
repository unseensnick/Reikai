package reikai.domain.merge

/**
 * [unified] with [opened] in it, for a reader opening a chapter the merged list does not show, most
 * often a sibling source's copy resumed from History. It takes the place of the copy [stitch] shows for
 * the same merged chapter, so its neighbours are that chapter's own: two sources count differently, so
 * a number is no guide. One the stitch places nowhere goes by its number, in the direction [byNumber]
 * says the list runs. Source order is restamped, since the reader sorts on it and the opened row still
 * carries its own source's.
 */
fun <T> withOpenedChapter(
    unified: List<T>,
    opened: T?,
    stitch: List<ChapterUnit>,
    id: (T) -> Long,
    byNumber: Comparator<T>,
    restamp: (T, Long) -> T,
): List<T> {
    if (opened == null || unified.any { id(it) == id(opened) }) return unified
    val unitOf = stitch.associate { it.chapterId to it.unit }
    val openedUnit = unitOf[id(opened)]
    val sameChapter = if (openedUnit == null) -1 else unified.indexOfFirst { unitOf[id(it)] == openedUnit }
    val placed = unified.toMutableList()
    if (sameChapter >= 0) {
        placed[sameChapter] = opened
    } else {
        val at = unified.indexOfFirst { byNumber.compare(it, opened) > 0 }
        placed.add(if (at >= 0) at else unified.size, opened)
    }
    return placed.mapIndexed { index, chapter -> restamp(chapter, index.toLong()) }
}
