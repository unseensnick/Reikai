package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CoroutineScope
import reikai.domain.entry.EntryId
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult
import reikai.presentation.browse.AddStep
import reikai.presentation.browse.DuplicatePrompt
import reikai.presentation.browse.EntryAddDialog
import reikai.presentation.browse.EntryAddFlow
import reikai.presentation.browse.components.EntryDuplicateCardUi
import reikai.presentation.browse.step
import reikai.presentation.details.detailsRoute
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category

/**
 * One entry the library already holds that an add might be duplicating. Carries its own [EntryId] so a
 * callback never rebuilds identity from the card's raw id, which overlaps across the two content types.
 */
@Immutable
data class RecentsDuplicate(val entry: EntryId, val card: EntryDuplicateCardUi)

/**
 * Everything the duplicate prompt needs, resolved by the provider that found them, so the engine holds
 * one neutral value instead of each type's own payload. [groupIdByRawId] is keyed the way the shared
 * dialog collapses same-group cards, by each duplicate's raw row id.
 */
@Immutable
data class RecentsDuplicates(
    val duplicates: List<RecentsDuplicate>,
    val groupIdByRawId: Map<Long, Long>,
    val suggestGroup: Boolean,
)

/** The adder's [DuplicatePrompt] in the engine's neutral shape; [entry] gives each duplicate its identity and card. */
fun <D, K> DuplicatePrompt<D, K>.toRecentsDuplicates(entry: (D) -> RecentsDuplicate) = RecentsDuplicates(
    duplicates = duplicates.map(entry),
    groupIdByRawId = groupIdByEntryId,
    suggestGroup = suggestGroup,
)

/** The recents half of [EntryAddDialog], carrying the typed entry each question acts on. */
sealed interface RecentsAddDialog {
    /** Ask before adding [entry], which looks like something the library already holds. */
    data class Duplicate(val entry: EntryId, val duplicates: RecentsDuplicates) : RecentsAddDialog

    /** Where a new add should be filed, when there is no usable default to file it into. */
    data class ChangeCategory(
        val entry: EntryId,
        val initialSelection: List<CheckboxState.State<Category>>,
    ) : RecentsAddDialog

    /** Migrate [current], a duplicate already in the library, onto the [target] being added. */
    data class Migrate(val current: EntryId, val target: EntryId) : RecentsAddDialog
}

/**
 * One content type's add flow on a recents row, over [provider]. The shared [EntryAddFlow] runs each
 * step's writes on the app scope and reports a failed add, as every other surface's does. An entry
 * already in the library is left alone rather than offered for removal: the button is only drawn for
 * one that is not, and the provider would otherwise refile it or toggle the favorite back off.
 */
class RecentsAddFlow(
    private val provider: RecentsProvider,
    scope: CoroutineScope,
    appScope: CoroutineScope,
) : EntryAddFlow<RecentsAddDialog>(scope, appScope, provider.contentType) {

    fun add(entry: EntryId) = launchStep {
        when (val decision = provider.addDecision(entry)) {
            null, AddDecision.Remove -> AddStep.Done
            is AddDecision.ConfirmDuplicate -> AddStep.Ask(RecentsAddDialog.Duplicate(entry, decision.duplicates))
            AddDecision.Add -> addOutright(entry)
        }
    }

    // Never raised, since [add] asks nothing about an entry already in the library.
    override fun confirmRemove() = Unit

    override fun confirmCategories(categoryIds: List<Long>) =
        continueWith(raised as? RecentsAddDialog.ChangeCategory) {
            provider.applyAddCategories(it.entry, categoryIds).step
        }

    // Asks again past the prompt, since the entry can reach the library while the prompt is open.
    override fun confirmAddDuplicate() = continueWith(raised as? RecentsAddDialog.Duplicate) {
        when (provider.addDecision(it.entry)) {
            null, AddDecision.Remove -> AddStep.Done
            else -> addOutright(it.entry)
        }
    }

    // A group add files into the group's categories, so it never asks.
    override fun addToGroup(entryIds: List<Long>) = continueWith(raised as? RecentsAddDialog.Duplicate) {
        provider.addToGroup(it.entry, it.duplicates.picked(entryIds)).step
    }

    override fun startMigrate(duplicateId: Long) = continueWith(raised as? RecentsAddDialog.Duplicate) {
        val current = it.duplicates.picked(listOf(duplicateId)).firstOrNull() ?: return@continueWith AddStep.Done
        AddStep.Ask(RecentsAddDialog.Migrate(current = current, target = it.entry))
    }

    override fun duplicateScreen(entryId: Long): NavKey = EntryId.of(contentType, entryId).detailsRoute()

    override fun RecentsAddDialog.toNeutral(): EntryAddDialog = when (this) {
        is RecentsAddDialog.Duplicate -> EntryAddDialog.AddDuplicate(
            duplicates = duplicates.duplicates.map { it.card },
            groupIdByEntryId = duplicates.groupIdByRawId,
            suggestGroup = duplicates.suggestGroup,
        )
        is RecentsAddDialog.ChangeCategory -> EntryAddDialog.ChangeCategory(initialSelection)
        is RecentsAddDialog.Migrate -> EntryAddDialog.Migrate(currentId = current.rawId, targetId = target.rawId)
    }

    private suspend fun addOutright(entry: EntryId): AddStep<RecentsAddDialog> =
        when (val result = provider.addToLibrary(entry)) {
            AddFavoriteResult.Added -> AddStep.Done
            AddFavoriteResult.Failed -> AddStep.Failed
            is AddFavoriteResult.NeedsCategoryChoice ->
                AddStep.Ask(RecentsAddDialog.ChangeCategory(entry, result.initialSelection))
        }

    /** The duplicates the dialog's cards name, by the identity each carries rather than a rebuilt one. */
    private fun RecentsDuplicates.picked(rawIds: List<Long>): List<EntryId> =
        duplicates.filter { it.card.id in rawIds }.map { it.entry }
}
