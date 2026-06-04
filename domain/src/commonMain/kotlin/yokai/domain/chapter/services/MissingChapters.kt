package yokai.domain.chapter.services

import kotlin.math.floor

/**
 * Count of whole chapters missing within a list of chapter numbers. Unknown numbers (-1) and
 * decimals are ignored (a 16.5 can't be "missing"). Ported from Mihon.
 */
fun List<Double>.missingChaptersCount(): Int {
    if (isEmpty()) return 0

    val chapters = this
        .filterNot { it == -1.0 }
        .map(Double::toInt)
        .distinct()
        .sorted()

    if (chapters.isEmpty()) return 0

    var missing = 0
    var previous = 0
    for (i in chapters.indices) {
        val current = chapters[i]
        if (current > previous + 1) missing += current - previous - 1
        previous = current
    }
    return missing
}

/**
 * Whole chapters skipped between two adjacent (number-sorted) chapters. Returns 0 when either number
 * is unknown (< 0); the caller renders an indicator only when the result is positive. Ported from Mihon.
 */
fun calculateChapterGap(higherChapterNumber: Double, lowerChapterNumber: Double): Int {
    if (higherChapterNumber < 0.0 || lowerChapterNumber < 0.0) return 0
    return floor(higherChapterNumber).toInt() - floor(lowerChapterNumber).toInt() - 1
}
