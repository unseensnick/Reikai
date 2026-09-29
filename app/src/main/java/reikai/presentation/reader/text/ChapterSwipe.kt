package reikai.presentation.reader.text

import kotlin.math.abs

/**
 * How far a swipe must travel sideways to step chapters, in dp. The WebView page reads it as
 * `SWIPE_MIN_PX` (reader.js); its document is initial-scale=1, so a CSS pixel there is a dp here.
 */
internal const val CHAPTER_SWIPE_MIN_DP = 180

/**
 * Which way a swipe steps chapters: true forward, false back, null for no step. It must be mostly
 * sideways, travel past [minimum], and start on the half it moves away from, so it crosses the middle
 * rather than flicking in a corner. LNReader's rule (core.js), strict comparisons included; reader.js
 * runs the same rule on the page, so the gesture matches in both renderers.
 */
internal fun chapterSwipeStep(dx: Float, dy: Float, startX: Float, width: Float, minimum: Float): Boolean? {
    if (abs(dx) <= minimum || abs(dx) <= abs(dy) * 2) return null
    val middle = width / 2
    return when {
        dx < 0 && startX >= middle -> true
        dx > 0 && startX <= middle -> false
        else -> null
    }
}
