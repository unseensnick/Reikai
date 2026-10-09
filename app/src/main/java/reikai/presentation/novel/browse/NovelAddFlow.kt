package reikai.presentation.novel.browse

import kotlinx.coroutines.CoroutineScope
import reikai.domain.library.ContentType
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.novel.host.NovelItem
import reikai.presentation.browse.DuplicatePrompt
import reikai.presentation.browse.EntryAddDialog
import reikai.presentation.browse.EntryAddFlow
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.toAddDuplicate
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category

/** The novel half of [EntryAddDialog], carrying the browsed result and the source it came from. */
sealed interface NovelBrowseDialog {
    data class AddDuplicate(
        val item: NovelItem,
        /** The source the result came from, so the confirm acts on the right one (varies in global search). */
        val sourceId: String,
        val prompt: DuplicatePrompt<NovelWithChapterCount, String>,
    ) : NovelBrowseDialog

    /** A browse add's picker. The add has written nothing, so its confirm stores, favorites and files [item]. */
    data class ChangeCategory(
        val item: NovelItem,
        val sourceId: String,
        val initialSelection: List<CheckboxState.State<Category>>,
    ) : NovelBrowseDialog
    data class RemoveNovel(val item: NovelItem, val sourceId: String) : NovelBrowseDialog

    /** Migrating the library's copy onto the one just browsed to, both already stored by id. */
    data class Migrate(val currentId: Long, val targetId: Long) : NovelBrowseDialog
}

/** The novel long-press add flow, over [NovelLibraryAdder]. */
class NovelAddFlow(
    private val adder: NovelLibraryAdder,
    scope: CoroutineScope,
) : EntryAddFlow<NovelBrowseDialog>(scope, ContentType.NOVELS) {

    fun onLongClick(item: NovelItem, sourceId: String) = launchStep { adder.onLongClick(item, sourceId) }

    override fun confirmRemove() = continueWith(raised as? NovelBrowseDialog.RemoveNovel) {
        adder.confirmRemove(it.item, it.sourceId)
        null
    }

    override fun confirmCategories(categoryIds: List<Long>) =
        continueWith(raised as? NovelBrowseDialog.ChangeCategory) {
            adder.confirmCategories(it.item, it.sourceId, categoryIds)
            null
        }

    override fun confirmAddDuplicate() = continueWith(raised as? NovelBrowseDialog.AddDuplicate) {
        adder.addToLibrary(it.item, it.sourceId)
    }

    override fun addToGroup(entryIds: List<Long>) = continueWith(raised as? NovelBrowseDialog.AddDuplicate) {
        adder.addToExistingGroup(it.item, it.sourceId, entryIds)
        null
    }

    // A browsed novel has no row until it is stored, which is a source round trip.
    override fun startMigrate(duplicateId: Long) = continueWith(raised as? NovelBrowseDialog.AddDuplicate) {
        adder.materialize(it.item, it.sourceId)
            ?.let { target -> NovelBrowseDialog.Migrate(currentId = duplicateId, targetId = target.id) }
    }

    // A novel is addressed by source and path, which only the raised dialog's duplicates still know.
    override fun duplicateScreen(entryId: Long) = (raised as? NovelBrowseDialog.AddDuplicate)
        ?.prompt
        ?.duplicates
        ?.firstOrNull { it.novel.id == entryId }
        ?.let { NovelScreen(it.novel.source, it.novel.url) }

    override fun NovelBrowseDialog.toNeutral(): EntryAddDialog = when (this) {
        is NovelBrowseDialog.RemoveNovel -> EntryAddDialog.Remove(item.name)
        is NovelBrowseDialog.ChangeCategory -> EntryAddDialog.ChangeCategory(initialSelection)
        is NovelBrowseDialog.AddDuplicate -> prompt.toAddDuplicate(NovelWithChapterCount::toDuplicateCard)
        is NovelBrowseDialog.Migrate -> EntryAddDialog.Migrate(currentId, targetId)
    }
}
