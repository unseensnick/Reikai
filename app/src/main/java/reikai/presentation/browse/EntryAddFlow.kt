package reikai.presentation.browse

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.domain.library.ContentType
import reikai.presentation.browse.components.EntryDuplicateCardUi
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.system.logcat
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

/** Where one step of the add flow leaves it: a question [D] to ask next, finished, or a write that failed. */
sealed interface AddStep<out D> {
    data class Ask<D>(val dialog: D) : AddStep<D>

    data object Done : AddStep<Nothing>

    /** Nothing more to ask, and the add did not happen, which the screen says. */
    data object Failed : AddStep<Nothing>
}

/** A finished add's step: the picker was answered, so only the favorite write is left to fail. */
val AddOutcome.step: AddStep<Nothing>
    get() = if (this == AddOutcome.Failed) AddStep.Failed else AddStep.Done

/**
 * One content type's long-press add flow, held by the model of every screen that lists entries, on
 * that model's scope, so an add left pending while a duplicate is opened is still there on return. Each
 * step's writes run on [appScope], so a step the dialog's own dismissal outlives still finishes.
 * [D] is the type's own dialog, which keeps what the neutral one drops (the entry, and for a novel the
 * source it came from). The shared dialogs dismiss before they confirm, so [dismiss] only hides the
 * question; what raised it stays until the flow's next step replaces it, and each verb reads it then.
 */
abstract class EntryAddFlow<D : Any>(
    private val scope: CoroutineScope,
    private val appScope: CoroutineScope,
    val contentType: ContentType,
) {
    val dialog: StateFlow<EntryAddDialog?>
        field = MutableStateFlow<EntryAddDialog?>(null)

    /**
     * One emission per add that failed. Not replayed: a failure nobody was on screen for is dropped
     * rather than toasted when a screen next collects, long after the add it names.
     */
    val failures: SharedFlow<Unit>
        field = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

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

    /** The details screen a duplicate card opens. */
    abstract fun duplicateScreen(entryId: Long): NavKey

    protected abstract fun D.toNeutral(): EntryAddDialog

    /**
     * Runs [step] and shows the question it asks, or closes the flow, saying so when the add failed.
     * A step that throws reads as failed: rethrown from the await on [scope], it would crash the app.
     */
    protected fun launchStep(step: suspend () -> AddStep<D>) {
        scope.launch {
            val next = runCatchingCancellable { appScope.async { step() }.await() }.getOrElse { e ->
                logcat(LogPriority.ERROR, e) { "A ${contentType.name.lowercase()} add step failed" }
                AddStep.Failed
            }
            show(next)
        }
    }

    /** Runs [step] against [current], read when the verb is called: never later, and never after a dismiss. */
    protected fun <T : D> continueWith(current: T?, step: suspend (T) -> AddStep<D>) {
        current ?: return
        launchStep { step(current) }
    }

    private fun show(next: AddStep<D>) {
        raised = (next as? AddStep.Ask)?.dialog
        dialog.value = raised?.toNeutral()
        if (next == AddStep.Failed) failures.tryEmit(Unit)
    }
}
