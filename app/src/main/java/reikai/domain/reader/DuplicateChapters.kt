package reikai.domain.reader

/**
 * Drop same-numbered duplicate chapters, which a merged entry produces once two of its sources carry the
 * same chapter. Of each group the chapter being read wins, then one from the same origin as it (a
 * scanlator for manga, a source for novels), then the first. Dropping them from the list rather than
 * stepping over them while navigating is what keeps the chapter sheet, download-ahead and delete-after-read
 * counting the chapters the reader will actually stop on.
 */
fun <T> List<T>.removeDuplicateChapters(
    current: T,
    numberOf: (T) -> Double,
    idOf: (T) -> Long,
    originOf: (T) -> String?,
): List<T> {
    val currentId = idOf(current)
    val currentOrigin = originOf(current)
    return groupBy(numberOf).map { (_, chapters) ->
        chapters.find { idOf(it) == currentId }
            ?: chapters.find { originOf(it) == currentOrigin }
            ?: chapters.first()
    }
}
