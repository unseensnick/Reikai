package reikai.presentation.browse

import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import reikai.domain.library.ContentType
import reikai.presentation.browse.components.EntryDuplicateCardUi
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category

/**
 * What a long press on a browse result can ask: take it out of the library, choose its categories,
 * confirm it against duplicates, or migrate onto one. Neutral, so [EntryAddDialogs] draws the same
 * four questions for both content types.
 */
sealed interface EntryAddDialog {
    data class Remove(val title: String) : EntryAddDialog

    data class ChangeCategory(val initialSelection: List<CheckboxState.State<Category>>) : EntryAddDialog

    data class AddDuplicate(
        val duplicates: List<EntryDuplicateCardUi>,
        /** Group id per duplicate entry id, for the "add to existing group" offer. */
        val groupIdByEntryId: Map<Long, Long>,
        /** The same-title grouping suggestion is on, so the dialog offers to join a group. */
        val suggestGroup: Boolean,
    ) : EntryAddDialog

    data class Migrate(val currentId: Long, val targetId: Long) : EntryAddDialog
}

/**
 * One content type's long-press add flow, held by the model of every screen that lists entries, on
 * that model's scope, so an add left pending while a duplicate is opened is still there on return.
 *
 * [D] is the type's own dialog, which keeps what the neutral one drops (the entry, and for a novel the
 * source it came from). The shared dialogs dismiss before they confirm, so [dismiss] only hides the
 * question; what raised it stays until the flow's next step replaces it, and each verb reads it then.
 */
abstract class EntryAddFlow<D : Any>(
    private val scope: CoroutineScope,
    val contentType: ContentType,
) {
    val dialog: StateFlow<EntryAddDialog?>
        field = MutableStateFlow<EntryAddDialog?>(null)

    @Volatile
    protected var raised: D? = null
        private set

    fun dismiss() {
        dialog.value = null
    }

    /** Take the entry out of the library, confirming [EntryAddDialog.Remove]. */
    abstract fun confirmRemove()

    /** File the entry under [categoryIds], which is what finishes an add the picker was raised for. */
    abstract fun confirmCategories(categoryIds: List<Long>)

    /** Add the entry despite the duplicates the dialog listed. */
    abstract fun confirmAddDuplicate()

    /** Add the entry to the group the picked duplicates belong to. */
    abstract fun addToGroup(entryIds: List<Long>)

    /** Migrate the duplicate [duplicateId] onto the entry the dialog was raised for. */
    abstract fun startMigrate(duplicateId: Long)

    /** The details screen a duplicate card opens, or null for an id the raised dialog did not list. */
    abstract fun duplicateScreen(entryId: Long): Screen?

    protected abstract fun D.toNeutral(): EntryAddDialog

    /** Runs [step] and shows the question it answers, or closes the flow when it answers null. */
    protected fun launchStep(step: suspend () -> D?) {
        scope.launchIO { show(step()) }
    }

    /** Runs [step] against [current], read when the verb is called: never later, and never after a dismiss. */
    protected fun <T : D> continueWith(current: T?, step: suspend (T) -> D?) {
        current ?: return
        launchStep { step(current) }
    }

    private fun show(next: D?) {
        raised = next
        dialog.value = next?.toNeutral()
    }
}
