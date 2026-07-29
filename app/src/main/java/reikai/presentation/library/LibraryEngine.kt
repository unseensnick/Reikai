package reikai.presentation.library

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.manga.DownloadAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import reikai.domain.category.categoryDiff
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.injectLazy

/**
 * Orchestrates the library over its per-type [LibraryProvider]s: it owns the selection and everything
 * derived from it, dispatches the bulk actions, and decides which provider drives a view, so the tab does
 * none of that itself.
 *
 * The selection lives here rather than in either content type's model because a combined list can hold
 * entries of both types at once, and a range-select can span them, which neither model can compute since
 * neither sees the other's rows. Entries are identified by [EntryId] for the same reason: a manga and a
 * novel can share a raw row id. Each provider narrows a dispatched selection to its own content type, so
 * handing every provider the whole selection is always safe.
 *
 * Shaped for a mixed list from the start: [providersFor] answers with every provider whose rows belong in
 * a view, which is one provider for Manga or Novels and both for [ContentType.ALL]. Only the single-type
 * case is wired today, because [behaviorFor] can only return one provider's behaviour and a mixed view
 * needs the two states combined. The category id spaces were the original blocker and no longer are: the
 * novel category table was folded into the shared `categories` table, so there is one id space and
 * `content_type` says which libraries a category belongs to. An ALL view still fails loudly rather than
 * silently rendering one content type; the library chip does not offer All yet, so it is unreachable.
 */
class LibraryEngine(private val providers: List<LibraryProvider>) : ScreenModel {

    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()

    private val mutableSelection = MutableStateFlow<Set<EntryId>>(emptySet())
    val selection: StateFlow<Set<EntryId>> = mutableSelection.asStateFlow()

    private val mutableDialog = MutableStateFlow<LibraryDialog?>(null)
    val dialog: StateFlow<LibraryDialog?> = mutableDialog.asStateFlow()

    /** Anchor for range-select; not reactive, it only decides how the next long-press behaves. */
    private var lastSelectionCategory: Long? = null

    /** Every provider contributing rows to a [contentType] view. Both of them for [ContentType.ALL]. */
    fun providersFor(contentType: ContentType): List<LibraryProvider> =
        providers.filter { contentType == ContentType.ALL || it.contentType == contentType }

    /** Of these providers, the ones that actually own an entry in [entries]. */
    private fun List<LibraryProvider>.owning(entries: Set<EntryId>): List<LibraryProvider> =
        filter { provider -> entries.any { it.contentType == provider.contentType } }

    /** The behaviour driving a [contentType] view. */
    fun behaviorFor(contentType: ContentType): LibraryBehavior =
        providersFor(contentType).singleOrNull()
            ?: error("A mixed $contentType library needs a behaviour combining both providers' state")

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

    // Dialogs. The selection stays until the dialog resolves, and each dialog carries the entries it was
    // built from, because both dialog composables dismiss before they confirm.

    fun dismissDialog() {
        mutableDialog.value = null
    }

    /**
     * A category must be assignable to *every* content type in the selection, so the lists are intersected
     * rather than merged. Nothing validates `content_type` on either join table, so assigning a manga-only
     * category to a novel would write a row that appears in no picker and that no confirm can remove (the
     * exclude list only ever holds ids the picker showed). A single-type selection intersects one list, so
     * this is exactly the per-type list there.
     */
    fun openChangeCategoryDialog(contentType: ContentType) {
        val entries = selection.value
        val targets = providersFor(contentType).owning(entries)
        if (targets.isEmpty()) return
        screenModelScope.launchIO {
            val assignable = targets
                .map { it.assignableCategories() }
                .reduce { acc, next ->
                    val ids = next.mapTo(HashSet()) { it.id }
                    acc.filter { it.id in ids }
                }
            val ordered = reikaiSortCategories(assignable, reikaiLibraryPreferences.categorySortOrder.get())
            val (common, mix) = categoryDiff(targets.flatMap { it.categoryIdsFor(entries) })
            val initialSelection = ordered.map {
                when (it.id) {
                    in common -> CheckboxState.State.Checked(it)
                    in mix -> CheckboxState.TriState.Exclude(it)
                    else -> CheckboxState.State.None(it)
                }
            }
            mutableDialog.value = LibraryDialog.ChangeCategory(entries, initialSelection)
        }
    }

    fun openDeleteDialog(contentType: ContentType) {
        val entries = selection.value
        val targets = providersFor(contentType).owning(entries)
        if (targets.isEmpty()) return
        mutableDialog.value = LibraryDialog.Delete(
            entries = entries,
            groupedSourceCount = targets.sumOf { it.groupedSourceCount(entries) },
            containsLocal = targets.any { it.containsLocal(entries) },
        )
    }

    /**
     * One sheet per content type until the two merge, so a mixed view fails loudly here for the same
     * reason [behaviorFor] does rather than silently configuring one of the two libraries.
     */
    fun openSettingsDialog(contentType: ContentType, categoryId: Long? = null, initialTab: Int = 0) {
        val provider = providersFor(contentType).singleOrNull()
            ?: error("A mixed $contentType library has no single settings sheet")
        mutableDialog.value = LibraryDialog.Settings(provider.contentType, categoryId, initialTab)
    }

    // Dialog confirms. Dispatched by the entries' own content types rather than the view's, since the
    // dialog outlives neither the selection nor the chip it was opened from.

    fun setCategories(entries: Set<EntryId>, addCategories: List<Long>, removeCategories: List<Long>) =
        providers.owning(entries).forEach { it.setCategories(entries, addCategories, removeCategories) }

    fun deleteEntries(
        entries: Set<EntryId>,
        deleteFromLibrary: Boolean,
        deleteDownloads: Boolean,
        removeGroupedSources: Boolean,
    ) = providers.owning(entries).forEach {
        it.deleteEntries(entries, deleteFromLibrary, deleteDownloads, removeGroupedSources)
    }

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
