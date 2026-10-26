package reikai.presentation.reader.text

import kotlin.math.abs

/**
 * How far a swipe must travel sideways to step chapters, in dp. The WebView page reads it as
 * `SWIPE_MIN_PX` (reader.js); its document is initial-scale=1, so a CSS pixel there is a dp here.
 */
internal const val CHAPTER_SWIPE_MIN_DP = 180

/** How many times its height a swipe must run sideways to step chapters; reader.js reads it as `SWIPE_SIDEWAYS`. */
internal const val CHAPTER_SWIPE_SIDEWAYS = 2

/**
 * How far a swipe must travel sideways to turn a page, in dp; reader.js reads it as `PAGE_SWIPE_MIN_PX`. Far
 * shorter than a chapter step, because paged it is the ordinary way to read on, as in manga's pager.
 */
internal const val PAGE_SWIPE_MIN_DP = 48

/**
 * Whether a touch has left where it went down. From there it is a drag, never a tap or a long press
 * however slowly it goes, so a swipe has no time limit. An Android view keeps its press until the
 * finger leaves its bounds, so the native renderer ends the press by this rule. reader.js runs it too,
 * over [slop] in dp, which NovelWebDocument fills in as `TOUCH_SLOP_PX`.
 */
internal fun hasTravelled(dx: Float, dy: Float, slop: Int): Boolean = abs(dx) > slop || abs(dy) > slop

/**
 * Which way a swipe turns a page: true forward (the finger moving left), false back, null for none. Mostly
 * sideways by [CHAPTER_SWIPE_SIDEWAYS] and past [minimum], from anywhere on the page; reader.js runs the same
 * rule, so paged reading turns alike in both renderers.
 */
internal fun pageSwipeStep(dx: Float, dy: Float, minimum: Float): Boolean? =
    if (abs(dx) <= minimum || abs(dx) <= abs(dy) * CHAPTER_SWIPE_SIDEWAYS) null else dx < 0

/**
 * Which way a swipe steps chapters: true forward, false back, null for no step. It must be mostly
 * sideways, travel past [minimum], and start on the half it moves away from, so it crosses the middle
 * rather than flicking in a corner. LNReader's rule (core.js), strict comparisons included; reader.js
 * runs the same rule on the page, so the gesture matches in both renderers. Neither renderer asks it,
 * nor takes a tap, for a touch that ever had a second finger down (TextViewportContractTest pins it).
 */
internal fun chapterSwipeStep(dx: Float, dy: Float, startX: Float, width: Float, minimum: Float): Boolean? {
    val forward = pageSwipeStep(dx, dy, minimum) ?: return null
    val middle = width / 2
    return forward.takeIf { if (it) startX >= middle else startX <= middle }
}
