package reikai.presentation.details

/**
 * Whether the details header names the whole merged group rather than one source: the unified view of
 * a group, where the label reads "All" and a library search by one source would pick a member at random.
 */
fun headerNamesWholeGroup(sourceCount: Int, selectedSource: Long?): Boolean = sourceCount > 1 && selectedSource == null
