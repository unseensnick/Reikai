package reikai.presentation.details

/**
 * Whether the details header names the whole merged group rather than one source: the unified view of
 * a group, where the label reads "All" and a library search by one source would pick a member at random.
 */
fun headerNamesWholeGroup(sourceCount: Int, selectedSource: Long?): Boolean = sourceCount > 1 && selectedSource == null

/**
 * The one entry the details page describes, feeding its header, synopsis and tags alike: the selected
 * merge chip's own entry, else the [anchor], with the custom-info [overlay] applied either way. Library
 * state (favourite, notes) and every write stay on the anchor.
 */
fun <T> shownEntry(anchor: T, chip: T?, overlay: (T) -> T): T = overlay(chip ?: anchor)
