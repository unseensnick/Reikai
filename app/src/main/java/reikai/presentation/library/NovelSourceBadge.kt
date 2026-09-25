package reikai.presentation.library

import reikai.novel.source.NovelSource

/**
 * A novel source as a cover badge draws it. A source that is no longer installed is [Missing], which
 * draws the warning badge a manga whose extension is gone gets.
 */
sealed interface NovelSourceBadge {
    data class Icon(val url: String) : NovelSourceBadge
    data object Missing : NovelSourceBadge
}

/** [source] is the registry's answer after its first load; null when an installed source has no icon. */
fun novelSourceBadge(source: NovelSource?): NovelSourceBadge? = when (source) {
    null -> NovelSourceBadge.Missing
    else -> source.iconUrl?.takeIf { it.isNotEmpty() }?.let(NovelSourceBadge::Icon)
}
