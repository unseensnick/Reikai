package reikai.presentation.details

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.SearchMetadataChips
import java.time.Instant

/**
 * Content-agnostic data for the shared details column ([entryInfoItems]). Each content type maps its
 * own loaded state into this, so the info box + action row + description emit identically for manga and
 * novels. Fields that only one type uses default to off (e.g. [showIntervalButton] false for novels).
 */
data class EntryDetailsUiState(
    val header: EntryHeaderUi,
    val favorite: Boolean,
    val trackingCount: Int,
    val showIntervalButton: Boolean,
    val nextUpdate: Instant?,
    val isUserIntervalMode: Boolean,
    val description: String?,
    val tags: List<String>?,
    val notes: String,
    val descriptionDefaultExpanded: Boolean,
)

/**
 * Emits the shared top of a details screen (info box, action row, an optional per-type card above the
 * description, then the description) into a [LazyListScope]. Both the manga and novel details screens
 * call this so the three can't drift; per-type content below (merge chips, related, previews, page bar,
 * chapters) stays each screen's own. [aboveDescription] is a per-type slot (manga's gallery-info card);
 * [searchMetadataChips] are manga's namespaced gallery tags.
 */
fun LazyListScope.entryInfoItems(
    isTabletUi: Boolean,
    appBarPadding: Dp,
    state: EntryDetailsUiState,
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    onAddToLibraryClicked: () -> Unit,
    onTrackingClicked: () -> Unit,
    onEditCategory: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onShareClicked: (() -> Unit)?,
    onTagSearch: (String) -> Unit,
    onGlobalSearch: ((String) -> Unit)?,
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
            doSearch = doSearch,
        )
    }
    item(key = "entry-action-row") {
        EntryActionRow(
            favorite = state.favorite,
            trackingCount = state.trackingCount,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onTrackingClicked = onTrackingClicked,
            onEditCategory = onEditCategory,
            showIntervalButton = state.showIntervalButton,
            nextUpdate = state.nextUpdate,
            isUserIntervalMode = state.isUserIntervalMode,
            onEditIntervalClicked = onEditIntervalClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onShareClicked = onShareClicked,
        )
    }
    if (aboveDescription != null) {
        item(key = "entry-above-description") { aboveDescription() }
    }
    item(key = "entry-description") {
        ExpandableMangaDescription(
            defaultExpandState = state.descriptionDefaultExpanded,
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
