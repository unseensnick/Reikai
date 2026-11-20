package reikai.presentation.browse

import kotlinx.coroutines.CoroutineScope
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.details.detailsRoute
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

/** The manga half of [EntryAddDialog], carrying the manga each question acts on. */
sealed interface MangaAddDialog {
    data class Remove(val manga: Manga) : MangaAddDialog

    data class AddDuplicate(
        val manga: Manga,
        val prompt: DuplicatePrompt<MangaWithChapterCount, Long>,
    ) : MangaAddDialog

    data class ChangeCategory(
        val manga: Manga,
        val initialSelection: List<CheckboxState.State<Category>>,
    ) : MangaAddDialog

    data class Migrate(val currentId: Long, val targetId: Long) : MangaAddDialog
}

/** The manga long-press add flow, over [MangaLibraryAdder]. */
class MangaAddFlow(
    private val adder: MangaLibraryAdder,
    scope: CoroutineScope,
    appScope: CoroutineScope,
) : EntryAddFlow<MangaAddDialog>(scope, appScope, ContentType.MANGA) {

    fun onLongClick(manga: Manga) = launchStep { adder.onLongClick(manga) }

    override fun confirmRemove() = continueWith(raised as? MangaAddDialog.Remove) {
        adder.removeFromLibrary(it.manga)
        AddStep.Done
    }

    override fun confirmCategories(categoryIds: List<Long>) =
        continueWith(raised as? MangaAddDialog.ChangeCategory) {
            adder.confirmAddCategories(it.manga.id, categoryIds).step
        }

    override fun confirmAddDuplicate() = continueWith(raised as? MangaAddDialog.AddDuplicate) {
        adder.addToLibrary(it.manga)
    }

    override fun addToGroup(entryIds: List<Long>) = continueWith(raised as? MangaAddDialog.AddDuplicate) {
        adder.addToExistingGroup(it.manga, entryIds).step
    }

    override fun startMigrate(duplicateId: Long) = continueWith(raised as? MangaAddDialog.AddDuplicate) {
        AddStep.Ask(MangaAddDialog.Migrate(currentId = duplicateId, targetId = it.manga.id))
    }

    override fun duplicateScreen(entryId: Long) = EntryId.Manga(entryId).detailsRoute()

    override fun MangaAddDialog.toNeutral(): EntryAddDialog = when (this) {
        is MangaAddDialog.Remove -> EntryAddDialog.Remove(manga.title)
        is MangaAddDialog.ChangeCategory -> EntryAddDialog.ChangeCategory(initialSelection)
        is MangaAddDialog.AddDuplicate -> prompt.toAddDuplicate(MangaWithChapterCount::toDuplicateCard)
        is MangaAddDialog.Migrate -> EntryAddDialog.Migrate(currentId, targetId)
    }
}

/** Where an add of [manga] leaves the flow: its picker when it has to ask. */
internal fun AddFavoriteResult.toStep(manga: Manga): AddStep<MangaAddDialog> =
    when (this) {
        AddFavoriteResult.Added -> AddStep.Done
        AddFavoriteResult.Failed -> AddStep.Failed
        is AddFavoriteResult.NeedsCategoryChoice -> AddStep.Ask(MangaAddDialog.ChangeCategory(manga, initialSelection))
    }
