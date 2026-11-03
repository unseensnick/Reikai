package reikai.presentation.details

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Every way the edit-info dialog adds tags (typed, filled from a tracker, collated from a merge's other
 * sources): the tags already there stay, since they may be the user's own, and the new ones are appended.
 * A tag matching another bar case or surrounding spaces is the same tag, and the spelling already on the
 * entry wins.
 */
fun appendTags(current: List<String>, added: List<String>): List<String> =
    (current + added).filter { it.isNotBlank() }.distinctBy { it.trim().lowercase() }

/** What the Add tag field holds, as tags: several may be typed at once, separated by commas. */
fun typedTags(input: String): List<String> = input.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Every tag a merged series' sources carry, for Collate tags; null for an entry that is not merged, which
 * hides the action rather than offering one that adds nothing.
 */
suspend fun groupSourceTags(groupIds: LongArray, tagsOf: suspend (Long) -> List<String>?): List<String>? =
    if (groupIds.size <= 1) null else groupIds.flatMap { tagsOf(it).orEmpty() }

/**
 * Where a dragged tag dropped at [point] goes, given each chip's bounds in order. A drop on a chip takes
 * its place, one past every chip goes to the end, and one in a gap between chips moves nothing. A bound
 * of [Rect.Zero] (a chip not laid out yet) takes no drop.
 */
fun tagDropIndex(bounds: List<Rect>, point: Offset): Int? {
    bounds.indexOfFirst { it.contains(point) }.takeIf { it >= 0 }?.let { return it }
    val pastEvery = bounds.isNotEmpty() &&
        bounds.all { point.y >= it.bottom || (point.y >= it.top && point.x >= it.right) }
    return if (pastEvery) bounds.lastIndex else null
}
