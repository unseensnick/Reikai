package reikai.presentation.recents

/**
 * What a recents surface is rendering. Created before anything persists one: the tab-shortcut step
 * stores this through `getEnum`, which writes the constant's own name and falls back to the default
 * when it reads a name it does not know, so renaming a case here resets every user's mode. Treat
 * these four names as the on-disk format.
 */
enum class RecentsMode {
    /** One row per title across every lane, newest activity first. */
    FEED,

    /** Each lane capped under its own header. */
    DIGEST,

    UPDATES,

    HISTORY,

    ;

    /**
     * Which lanes this mode draws from, and therefore which feeds a surface rendering it has to open.
     * A single-lane mode collects one, which is what keeps the two-tab shape from running every query.
     */
    val lanes: Set<RecentsLaneKind>
        get() = when (this) {
            FEED, DIGEST -> setOf(RecentsLaneKind.READ, RecentsLaneKind.UPDATED, RecentsLaneKind.ADDED)
            UPDATES -> setOf(RecentsLaneKind.UPDATED)
            HISTORY -> setOf(RecentsLaneKind.READ)
        }

    /**
     * Whether rows here can be selected. History has never had selection and does not gain it at the
     * cutover, so declaring it per mode is what stops the shared shell offering an action mode that
     * reaches nothing.
     */
    val supportsSelection: Boolean
        get() = this == UPDATES
}
