package reikai.presentation.library

/**
 * Library grouping modes (dynamic grouping). Values are kept identical to the Yōkai-era fork
 * so the persisted `group_library_by` preference round-trips on an in-place upgrade.
 *
 * Only the constants are ported here; the group-picker's string/drawable resources are wired in
 * the settings sheet (Stage 4).
 */
object LibraryGroup {

    const val BY_DEFAULT = 0
    const val BY_TAG = 1
    const val BY_SOURCE = 2
    const val BY_STATUS = 3
    const val BY_TRACK_STATUS = 4
    const val UNGROUPED = 5
    const val BY_AUTHOR = 6
    const val BY_LANGUAGE = 7
}
