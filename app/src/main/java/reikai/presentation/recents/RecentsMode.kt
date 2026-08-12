package reikai.presentation.recents

/**
 * What a recents surface is rendering, and what it comes back to. Persisted through `getEnum`, which
 * writes the constant's own name and falls back to the default when it reads a name it does not know,
 * so renaming a case here resets every user's mode. Treat these four names as the on-disk format.
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
     * What this mode offers. One typed set rather than a Boolean per affordance: the shell asks about
     * several of these, and a row of flags is the shape that lets two of them drift into an illegal
     * combination nobody rules on. A capability a mode lacks is not drawn, never drawn disabled.
     */
    val capabilities: Set<RecentsCapability>
        get() = when (this) {
            // A history feed carries no chapter state to filter on and no burst to group, and its
            // selection arrives with the affordances that act on one. Its category filter is not a
            // capability: every mode has one, so the sheet draws that part unconditionally.
            HISTORY -> emptySet()
            UPDATES -> setOf(RecentsCapability.SELECTION, RecentsCapability.CHAPTER_FILTER, RecentsCapability.GROUPING)
            FEED, DIGEST -> setOf(RecentsCapability.SELECTION, RecentsCapability.CHAPTER_FILTER)
        }

    fun can(capability: RecentsCapability): Boolean = capability in capabilities
}

/**
 * An affordance a mode either has or does not. [CHAPTER_FILTER] and [GROUPING] are what the filter
 * sheet asks about before drawing its chapter-state block and its grouping switch; [GROUPING] is the
 * Updates mode's own, since the combined modes have no ungrouped reading to switch to.
 */
enum class RecentsCapability { SELECTION, CHAPTER_FILTER, GROUPING }
