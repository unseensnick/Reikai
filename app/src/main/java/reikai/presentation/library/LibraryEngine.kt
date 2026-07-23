package reikai.presentation.library

import eu.kanade.presentation.manga.DownloadAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType

/**
 * Orchestrates the library over its per-type [LibraryProvider]s: it owns the selection, dispatches the
 * bulk actions, and decides which provider drives a view, so the tab does none of that itself.
 *
 * The selection lives here rather than in either content type's model because a combined list can hold
 * entries of both types at once, and a range-select can span them, which neither model can compute since
 * neither sees the other's rows. Entries are identified by [EntryId] for the same reason: a manga and a
 * novel can share a raw row id. Each provider narrows a dispatched selection to its own content type, so
 * handing every provider the whole selection is always safe.
 *
 * Shaped for a mixed list from the start: [providersFor] answers with every provider whose rows belong in
 * a view, which is one provider for Manga or Novels and both for [ContentType.ALL]. Only the single-type
 * case is wired today, because a mixed view additionally needs the manga and novel category id spaces
 * unified (they allocate independently, so the id 3 exists in both meaning different things). An ALL view
 * therefore fails loudly rather than silently rendering one content type; the library chip does not offer
 * All yet, so it is unreachable.
 */
class LibraryEngine(private val providers: List<LibraryProvider>) {

    private val mutableSelection = MutableStateFlow<Set<EntryId>>(emptySet())
    val selection: StateFlow<Set<EntryId>> = mutableSelection.asStateFlow()

    /** Anchor for range-select; not reactive, it only decides how the next long-press behaves. */
    private var lastSelectionCategory: Long? = null

    /** Every provider contributing rows to a [contentType] view. Both of them for [ContentType.ALL]. */
    fun providersFor(contentType: ContentType): List<LibraryProvider> =
        providers.filter { contentType == ContentType.ALL || it.contentType == contentType }

    /** The behaviour driving a [contentType] view. */
    fun behaviorFor(contentType: ContentType): LibraryBehavior =
        providersFor(contentType).singleOrNull()
            ?: error("A mixed $contentType library needs one category id space across content types")

    // Selection. Every op that needs to know what is on screen takes the category's entries in display
    // order, so the engine never has to resolve rows itself and stays free of per-type lookups.

    fun clearSelection() {
        lastSelectionCategory = null
        mutableSelection.value = emptySet()
    }

    fun toggleSelection(categoryId: Long, entry: EntryId) {
        mutableSelection.update { if (entry in it) it - entry else it + entry }
        lastSelectionCategory = categoryId.takeIf { mutableSelection.value.isNotEmpty() }
    }

    /**
     * Select every entry between [entry] and the last selected one, within one category. Falls back to
     * selecting just [entry] when there is no usable anchor, which is what a long-press in a different
     * category (or on a row that is no longer listed) means.
     */
    fun toggleRangeSelection(categoryId: Long, entry: EntryId, ordered: List<EntryId>) {
        mutableSelection.update { current ->
            val anchor = current.lastOrNull()
            val from = ordered.indexOf(anchor)
            val to = ordered.indexOf(entry)
            if (lastSelectionCategory != categoryId || anchor == null || from < 0 || to < 0) {
                current + entry
            } else {
                current + ordered.subList(minOf(from, to), maxOf(from, to) + 1)
            }
        }
        lastSelectionCategory = categoryId
    }

    fun selectAll(ordered: List<EntryId>) {
        lastSelectionCategory = null
        mutableSelection.update { it + ordered }
    }

    /** Select every entry in one category, or deselect them when all are already selected. */
    fun selectAllInCategory(ordered: List<EntryId>) {
        lastSelectionCategory = null
        mutableSelection.update { current ->
            if (ordered.isNotEmpty() && ordered.all { it in current }) {
                current - ordered.toSet()
            } else {
                current + ordered
            }
        }
    }

    fun invertSelection(ordered: List<EntryId>) {
        lastSelectionCategory = null
        mutableSelection.update { current ->
            val (toRemove, toAdd) = ordered.partition { it in current }
            current - toRemove.toSet() + toAdd
        }
    }

    // Bulk actions. Each is handed to every provider in the view, which narrows it to its own entries,
    // so one call covers a selection spanning both content types.

    fun markReadSelection(contentType: ContentType, read: Boolean) =
        dispatchAndClear(contentType) { it.markReadSelection(selection.value, read) }

    fun performDownloadAction(contentType: ContentType, action: DownloadAction) =
        dispatchAndClear(contentType) { it.performDownloadAction(selection.value, action) }

    fun mergeSelection(contentType: ContentType) =
        dispatchAndClear(contentType) { it.mergeSelection(selection.value) }

    fun unmergeSelection(contentType: ContentType) =
        dispatchAndClear(contentType) { it.unmergeSelection(selection.value) }

    // These two only open a per-type dialog, so the selection stays until the dialog resolves.
    fun openChangeCategoryDialog(contentType: ContentType) =
        providersFor(contentType).forEach { it.openChangeCategoryDialog(selection.value) }

    fun openDeleteDialog(contentType: ContentType) =
        providersFor(contentType).forEach { it.openDeleteDialog(selection.value) }

    /** Any selected entry is a merge group; drives the bulk Unmerge action. */
    fun selectionContainsMerged(contentType: ContentType): Boolean =
        providersFor(contentType).any { it.containsMerged(selection.value) }

    /** The bulk Download action applies (manga hides it when every selected entry is local). */
    fun canDownloadSelection(contentType: ContentType): Boolean =
        providersFor(contentType).all { it.canDownload(selection.value) }

    private fun dispatchAndClear(contentType: ContentType, action: (LibraryProvider) -> Unit) {
        providersFor(contentType).forEach(action)
        clearSelection()
    }
}
