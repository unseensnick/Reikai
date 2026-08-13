package reikai.presentation.recents

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

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
            // A history feed has no burst to group, so no GROUPING. It does take a selection: the
            // combined modes already give a read row one, and every verb behind it acts on the
            // chapter that row names rather than on an update burst. Withholding it here made the
            // same row answer differently depending on which tab drew it. It could also answer the
            // chapter-state filters (its rows carry that state), but the four preferences behind them
            // are the Updates view's, and obeying them unprompted would narrow this feed silently.
            HISTORY -> setOf(RecentsCapability.SELECTION)
            UPDATES -> setOf(RecentsCapability.SELECTION, RecentsCapability.CHAPTER_FILTER, RecentsCapability.GROUPING)
            FEED, DIGEST -> setOf(RecentsCapability.SELECTION, RecentsCapability.CHAPTER_FILTER)
        }

    fun can(capability: RecentsCapability): Boolean = capability in capabilities

    val labelRes: StringResource
        get() = when (this) {
            FEED -> MR.strings.recents_mode_feed
            DIGEST -> MR.strings.recents_mode_grouped
            UPDATES -> MR.strings.label_recent_updates
            HISTORY -> MR.strings.history
        }

    /**
     * What an empty feed says, which is the lanes' answer rather than the mode's: a view of one lane
     * names that lane, and one mixing several can only speak of activity. One line for every mode is
     * how History came to report itself as having no recent updates.
     */
    val emptyRes: StringResource
        get() = when {
            lanes.size > 1 -> MR.strings.information_no_recent_activity
            RecentsLaneKind.READ in lanes -> MR.strings.information_no_recent_manga
            else -> MR.strings.information_no_recent
        }
}

/**
 * Whether this mode mixes lanes, which is what makes it a suggestion of what to read next rather than
 * a record of one kind of event. Derived from the lanes so a mode added later cannot forget to say.
 */
val RecentsMode.isCombined: Boolean
    get() = lanes.size > 1

/**
 * The order the mode switcher draws, widest view first. Deliberately not the declaration order,
 * which decides the fallback mode and is therefore not free to rearrange.
 */
val RECENTS_MODE_ORDER = listOf(
    RecentsMode.DIGEST,
    RecentsMode.FEED,
    RecentsMode.HISTORY,
    RecentsMode.UPDATES,
)

/**
 * An affordance a mode either has or does not. [CHAPTER_FILTER] and [GROUPING] are what the filter
 * sheet asks about before drawing its chapter-state block and its grouping switch; [GROUPING] is the
 * Updates mode's own, since the combined modes have no ungrouped reading to switch to.
 */
enum class RecentsCapability { SELECTION, CHAPTER_FILTER, GROUPING }
