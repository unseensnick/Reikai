package reikai.presentation.details

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import eu.kanade.presentation.manga.components.SearchMetadataChips
import kotlin.time.Instant

/**
 * Content-agnostic data for the shared details column ([entryInfoItems]). Each content type maps its
 * own loaded state into this, so the info box + action row + description emit identically for manga and
 * novels.
 */
data class EntryDetailsUiState(
    val header: EntryHeaderUi,
    val favorite: Boolean,
    val trackingCount: Int,
    val nextUpdate: Instant?,
    val isUserIntervalMode: Boolean,
    val description: String?,
    val tags: List<String>?,
    val notes: String,
    val descriptionDefaultExpanded: Boolean,
)

/**
 * Emits the shared top of a details screen (info box, action row, an optional per-type card above the
 * description, then the description) into a [LazyListScope], for EntryDetailsContent, which renders
 * the rest of the body itself. [aboveDescription] is a per-type slot (manga's gallery-info card);
 * [searchMetadataChips] are manga's namespaced gallery tags.
 */
fun LazyListScope.entryInfoItems(
    isTabletUi: Boolean,
    appBarPadding: Dp,
    state: EntryDetailsUiState,
    onCoverClick: () -> Unit,
    onGlobalSearch: (query: String) -> Unit,
    librarySearch: (query: String) -> Unit,
    onBrowseSource: (() -> Unit)?,
    onAddToLibraryClicked: () -> Unit,
    onTrackingClicked: () -> Unit,
    onEditCategory: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (String) -> Unit,
    onEditNotes: () -> Unit,
    searchMetadataChips: SearchMetadataChips? = null,
    aboveDescription: (@Composable () -> Unit)? = null,
) {
    item(key = "entry-info-box") {
        EntryInfoBox(
            isTabletUi = isTabletUi,
            appBarPadding = appBarPadding,
            header = state.header,
            onCoverClick = onCoverClick,
            onGlobalSearch = onGlobalSearch,
            librarySearch = librarySearch,
            onBrowseSource = onBrowseSource,
        )
    }
    item(key = "entry-action-row") {
        EntryActionRow(
            favorite = state.favorite,
            trackingCount = state.trackingCount,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onTrackingClicked = onTrackingClicked,
            onEditCategory = onEditCategory,
            nextUpdate = state.nextUpdate,
            isUserIntervalMode = state.isUserIntervalMode,
            onEditIntervalClicked = onEditIntervalClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
        )
    }
    if (aboveDescription != null) {
        item(key = "entry-above-description") { aboveDescription() }
    }
    item(key = "entry-description") {
        ExpandableEntryDescription(
            // Expansion is a layout input as well as a state one: the two-pane layout always opens
            // expanded, since the description has its own pane to fill and nothing to crowd.
            defaultExpandState = isTabletUi || state.descriptionDefaultExpanded,
            description = state.description,
            tagsProvider = { state.tags },
            notes = state.notes,
            onTagSearch = onTagSearch,
            onCopyTagToClipboard = onCopyTagToClipboard,
            onEditNotes = onEditNotes,
            onGlobalSearch = onGlobalSearch,
            searchMetadataChips = searchMetadataChips,
        )
    }
}
