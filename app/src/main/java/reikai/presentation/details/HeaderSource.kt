package reikai.presentation.details

/**
 * Whether the details header names the whole merged group rather than one source: the unified view of
 * a group, where the label reads "All" and a library search by one source would pick a member at random.
 */
fun headerNamesWholeGroup(sourceCount: Int, selectedSource: Long?): Boolean = sourceCount > 1 && selectedSource == null

/**
 * The one entry the details page describes, feeding its header, synopsis and tags alike: the selected
 * [sibling] chip's own entry (never the anchor's own chip), else the [anchor], with the custom-info
 * [overlay] applied either way. A sibling keeps its own cover through [ownCover]: a custom cover belongs
 * to the entry the library renders, and the cover viewer opens the sibling's. Library state (favourite,
 * notes) and every write stay on the anchor.
 */
fun <T> shownEntry(anchor: T, sibling: T?, overlay: (T) -> T, ownCover: (shown: T, sibling: T) -> T): T =
    sibling?.let { ownCover(overlay(it), it) } ?: overlay(anchor)
