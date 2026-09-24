package reikai.domain.reader

/**
 * The chapter "after reading automatically delete" retires once the one with [currentId] is finished:
 * the one [slots] positions back in the order the reader pages through, so reading forward keeps a
 * rolling window on disk. Null when the setting is off (negative slots), the current chapter is not in
 * the list, or the window has not filled yet. Both readers pick their target with this.
 */
fun <T> List<T>.chapterToDeleteBehind(currentId: Long, slots: Int, idOf: (T) -> Long): T? {
    if (slots < 0) return null
    val index = indexOfFirst { idOf(it) == currentId }
    if (index < 0) return null
    return getOrNull(index - slots)
}
