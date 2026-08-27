package reikai.presentation.browse.catalogue

import androidx.compose.runtime.Immutable
import reikai.presentation.browse.components.EntryDuplicateCardUi
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode

/**
 * The neutral catalogue state both content types produce, so one screen can render a manga source and
 * a light-novel source without branching on type. Each adapter maps its own model into [Loaded]; the
 * two failure cases are separate because they recover differently.
 */
sealed interface EntryBrowseScreenState {
    data object Loading : EntryBrowseScreenState

    /** The source cannot be reached and nothing here will bring it back: an extension that is not
     *  installed. No retry is offered, because there is nothing to retry. */
    data class SourceMissing(val label: String) : EntryBrowseScreenState

    /** The source failed to load but can be re-resolved, which [reload] does. Only the novel adapter
     *  produces this: a plugin that threw while loading has no pager to report through. [reload] is
     *  held once by that adapter, so an emission carrying it does not break this state's equality. */
    data class SourceFailed(val message: String, val reload: () -> Unit) : EntryBrowseScreenState

    @Immutable
    data class Loaded(
        val sourceName: String,
        /** What the pager is currently paging. */
        val listing: EntryBrowseListing,
        /** The in-toolbar search text, which is not yet committed; null closes the search field. */
        val query: String?,
        /** A search the reader typed, so Back clears it before it leaves the screen. */
        val isUserQuery: Boolean,
        /** This source can list Latest. False hides the chip rather than showing it disabled. */
        val supportsLatest: Boolean,
        /** This source declares filters, so the Filter chip is drawn at all. */
        val hasFilters: Boolean,
        /** A filter differs from the source's own defaults, so the Filter chip reads as active. */
        val filtersActive: Boolean,
        /** This source has settings of its own, so the Settings action is offered. */
        val hasSettings: Boolean,
        /** How result rows are drawn, which also carries the display mode when there is one. */
        val rowStyle: EntryBrowseRowStyle,
        /** Bulk-selection is on. True with an empty [selectedKeys] right after Select is tapped. */
        val selectionMode: Boolean,
        /** [EntryBrowseRow.key]s of the selected rows. */
        val selectedKeys: Set<String>,
        val capabilities: EntryBrowseCapabilities,
        val dialog: EntryBrowseDialog? = null,
    ) : EntryBrowseScreenState
}

/** Which listing the source is showing. Filters are absent on purpose: they are dispatched through
 *  the per-type filter sheet, so no neutral state can hold their values. */
sealed interface EntryBrowseListing {
    data object Popular : EntryBrowseListing
    data object Latest : EntryBrowseListing
    data class Search(val query: String?) : EntryBrowseListing
}

/**
 * How the result rows are laid out. [Gallery] is the adult-source layout, which brings its own row
 * shape and has no display mode to offer, so the display-mode menu is hidden under it rather than
 * shown doing nothing. Only the manga adapter produces it.
 */
sealed interface EntryBrowseRowStyle {
    data class Standard(val displayMode: LibraryDisplayMode) : EntryBrowseRowStyle
    data object Gallery : EntryBrowseRowStyle
}

/**
 * Per-type slots for the catalogue screen. Each adapter fills only what its type supports, and an
 * absent slot hides its affordance rather than showing it disabled. Adding one later is additive
 * rather than a change to the shared spine.
 */
@Immutable
data class EntryBrowseCapabilities(
    /** Manga only: the MangaDex Follows and Random entries the filter sheet offers. */
    val mangaDex: MangaDexBrowseCapability? = null,
    /** Novels only: browsing to choose a migration target. */
    val migrationPick: MigrationPickCapability? = null,
)

data class MangaDexBrowseCapability(val sourceId: Long)

/**
 * Browsing to choose what [migrateForId] moves to, so a tap reports the pick back and pops instead of
 * opening the entry. A plain class held once by its adapter, not a data class: it carries an action,
 * and re-creating it per state emission would break the enclosing state's equality.
 */
class MigrationPickCapability(
    val migrateForId: Long,
    val pick: (EntryBrowseRow, onPicked: () -> Unit) -> Unit,
)

/**
 * The dialogs the catalogue screen raises. Payloads are neutral, so every action keys on an id the
 * adapter fans back out to its own model, or on the entry its own state already remembers.
 */
sealed interface EntryBrowseDialog {
    /** The source's own filter sheet, dispatched per type because the filter shapes have nothing in
     *  common: a typed `FilterList` on one side, a plugin JSON schema on the other. */
    data object Filter : EntryBrowseDialog

    data class Remove(val title: String) : EntryBrowseDialog

    data class ChangeCategory(val initialSelection: List<CheckboxState.State<Category>>) : EntryBrowseDialog

    data class AddDuplicate(
        val duplicates: List<EntryDuplicateCardUi>,
        /** Group id per duplicate entry id, for the "add to existing group" offer. */
        val groupIdByEntryId: Map<Long, Long>,
        /** The same-title grouping suggestion is on, so the dialog offers to join a group. */
        val suggestGroup: Boolean,
    ) : EntryBrowseDialog

    data class Migrate(val currentId: Long, val targetId: Long) : EntryBrowseDialog
}
