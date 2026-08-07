package reikai.presentation.library

import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category

/**
 * The library dialogs, built by [LibraryEngine] rather than by either content type's model, because
 * anything derived from the selection must be derived where the selection lives: a combined list holds
 * both types, and two independently computed change-categories preselections cannot be merged after the
 * fact, only recomputed over the union. [ChangeCategory] and [Delete] carry the entries they act on
 * rather than reading the live selection at confirm time, since both dialog composables dismiss before
 * they confirm, so the dialog is already gone by the time the confirm runs.
 */
sealed interface LibraryDialog {

    /** [initialSelection] is already ordered and filtered; the dialog renders it as given. */
    data class ChangeCategory(
        val entries: Set<EntryId>,
        val initialSelection: List<CheckboxState<Category>>,
    ) : LibraryDialog

    /**
     * [groupedSourceCount] is the number of grouped sources behind the selection (0 = nothing merged, so
     * no extra option). [containsLocal] hides the delete-downloads option, which a local entry has no use
     * for; novels have no local concept and never set it.
     */
    data class Delete(
        val entries: Set<EntryId>,
        val groupedSourceCount: Int,
        val containsLocal: Boolean,
    ) : LibraryDialog

    /**
     * [contentType] picks which settings sheet renders, because the novel sheet still takes the novel
     * ViewModel as a parameter and cannot be neutralized until the two sheets merge. Keying the render
     * branch on the dialog rather than on the ambient chip is what a mixed list needs anyway.
     *
     * A null [categoryId] is the global-sort scope, not a stale active category.
     */
    data class Settings(
        val contentType: ContentType,
        val categoryId: Long?,
        val initialTab: Int,
    ) : LibraryDialog
}
