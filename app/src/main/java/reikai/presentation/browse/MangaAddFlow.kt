package reikai.presentation.browse

import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.CoroutineScope
import reikai.domain.library.ContentType
import reikai.presentation.browse.components.EntrySourceLabel
import reikai.presentation.browse.components.toDuplicateCard
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

/** The manga half of [EntryAddDialog], carrying the manga each question acts on. */
sealed interface MangaAddDialog {
    data class Remove(val manga: Manga) : MangaAddDialog

    data class AddDuplicate(
        val manga: Manga,
        val duplicates: List<MangaWithChapterCount>,
        val suggestGroup: Boolean,
        val groupIdByMangaId: Map<Long, Long>,
        val sourceLabels: Map<Long, EntrySourceLabel>,
    ) : MangaAddDialog

    data class ChangeCategory(
        val manga: Manga,
        val initialSelection: List<CheckboxState.State<Category>>,
        /** The group of the duplicate dialog's picks, when the add joins one: its confirm then merges too. */
        val joinGroup: List<Long> = emptyList(),
    ) : MangaAddDialog

    data class Migrate(val currentId: Long, val targetId: Long) : MangaAddDialog
}

/** The manga long-press add flow, over [MangaLibraryAdder]. */
class MangaAddFlow(
    private val adder: MangaLibraryAdder,
    scope: CoroutineScope,
) : EntryAddFlow<MangaAddDialog>(scope, ContentType.MANGA) {

    fun onLongClick(manga: Manga) = launchStep { adder.onLongClick(manga) }

    override fun confirmRemove() = continueWith(raised as? MangaAddDialog.Remove) {
        adder.removeFromLibrary(it.manga)
        null
    }

    override fun confirmCategories(categoryIds: List<Long>) =
        continueWith(raised as? MangaAddDialog.ChangeCategory) {
            adder.confirmPicker(it.manga, categoryIds, it.joinGroup)
            null
        }

    override fun confirmAddDuplicate() = continueWith(raised as? MangaAddDialog.AddDuplicate) {
        adder.addToLibrary(it.manga)
    }

    override fun addToGroup(entryIds: List<Long>) = continueWith(raised as? MangaAddDialog.AddDuplicate) {
        adder.addToExistingGroup(it.manga, entryIds).pickerFor(it.manga, joinGroup = entryIds)
    }

    override fun startMigrate(duplicateId: Long) = continueWith(raised as? MangaAddDialog.AddDuplicate) {
        MangaAddDialog.Migrate(currentId = duplicateId, targetId = it.manga.id)
    }

    override fun duplicateScreen(entryId: Long) = MangaScreen(entryId)

    override fun MangaAddDialog.toNeutral(): EntryAddDialog = when (this) {
        is MangaAddDialog.Remove -> EntryAddDialog.Remove(manga.title)
        is MangaAddDialog.ChangeCategory -> EntryAddDialog.ChangeCategory(initialSelection)
        is MangaAddDialog.AddDuplicate -> EntryAddDialog.AddDuplicate(
            duplicates = duplicates.map { it.toDuplicateCard(sourceLabels) },
            groupIdByEntryId = groupIdByMangaId,
            suggestGroup = suggestGroup,
        )
        is MangaAddDialog.Migrate -> EntryAddDialog.Migrate(currentId, targetId)
    }
}

/** The picker an add has to raise, or null when it finished (or failed, which wrote nothing). */
internal fun AddFavoriteResult.pickerFor(manga: Manga, joinGroup: List<Long> = emptyList()): MangaAddDialog? =
    when (this) {
        AddFavoriteResult.Added, AddFavoriteResult.Failed -> null
        is AddFavoriteResult.NeedsCategoryChoice -> MangaAddDialog.ChangeCategory(manga, initialSelection, joinGroup)
    }
