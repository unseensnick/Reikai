package reikai.presentation.library

/**
 * Library grouping modes (dynamic grouping). The values are stored in the `group_library_by`
 * preference and restored verbatim from backups, so renumbering one needs a migration.
 *
 * Only the constants live here; the group picker's labels are wired in the settings sheet.
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
