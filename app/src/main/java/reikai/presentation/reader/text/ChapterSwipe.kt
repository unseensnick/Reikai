package reikai.presentation.reader.text

import kotlin.math.abs

/**
 * How far a swipe must travel sideways to step chapters, in dp. The WebView page reads it as
 * `SWIPE_MIN_PX` (reader.js); its document is initial-scale=1, so a CSS pixel there is a dp here.
 */
internal const val CHAPTER_SWIPE_MIN_DP = 180

/**
 * Whether a touch has left where it went down. From there it is a drag, never a tap or a long press
 * however slowly it goes, so a swipe has no time limit. The WebView page has this from its browser;
 * an Android view keeps its press until the finger leaves its bounds, so the native renderer ends
 * the press by this rule.
 */
internal fun hasTravelled(dx: Float, dy: Float, slop: Int): Boolean = abs(dx) > slop || abs(dy) > slop

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
