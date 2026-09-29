package reikai.presentation.novel.browse

import kotlinx.coroutines.CoroutineScope
import reikai.domain.library.ContentType
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.novel.host.NovelItem
import reikai.presentation.browse.EntryAddDialog
import reikai.presentation.browse.EntryAddFlow
import reikai.presentation.browse.components.EntrySourceLabel
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category

/** The novel half of [EntryAddDialog], carrying the browsed result and the source it came from. */
sealed interface NovelBrowseDialog {
    data class AddDuplicate(
        val item: NovelItem,
        /** The source the result came from, so the confirm acts on the right one (varies in global search). */
        val sourceId: String,
        val duplicates: List<NovelWithChapterCount>,
        /** Source id -> its label for each duplicate (resolved in the adder, so the dialog is DI-free). */
        val sourceLabels: Map<String, EntrySourceLabel>,
        /** Whether to offer add-time grouping (the same-title suggestion pref plus the master switch). */
        val suggestGroup: Boolean,
        /** Novel id -> group id, so same-group duplicates collapse into one card. */
        val groupIdByNovelId: Map<Long, Long>,
    ) : NovelBrowseDialog
    data class ChangeCategory(
        val target: NovelCategoryTarget,
        val initialSelection: List<CheckboxState.State<Category>>,
    ) : NovelBrowseDialog
    data class RemoveNovel(val item: NovelItem, val sourceId: String) : NovelBrowseDialog

    /** Migrating the library's copy onto the one just browsed to, both already stored by id. */
    data class Migrate(val currentId: Long, val targetId: Long) : NovelBrowseDialog
}

/**
 * What a category picker's confirm has left to write. Both adds reach the picker before anything is
 * written, so backing out of it adds nothing and confirming owes the whole add; a group add's favorite
 * also merges its already inserted row into the group of [JoinGroup.selectedIds], as one unit.
 */
sealed interface NovelCategoryTarget {
    data class JoinGroup(val novelId: Long, val selectedIds: List<Long>) : NovelCategoryTarget
    data class Pending(val item: NovelItem, val sourceId: String) : NovelCategoryTarget
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
            adder.confirmCategories(it.target, categoryIds)
            null
        }

    override fun confirmAddDuplicate() = continueWith(raised as? NovelBrowseDialog.AddDuplicate) {
        adder.addToLibrary(it.item, it.sourceId)
    }

    override fun addToGroup(entryIds: List<Long>) = continueWith(raised as? NovelBrowseDialog.AddDuplicate) {
        adder.addToExistingGroup(it.item, it.sourceId, entryIds)
    }

    // A browsed novel has no row until it is stored, which is a source round trip.
    override fun startMigrate(duplicateId: Long) = continueWith(raised as? NovelBrowseDialog.AddDuplicate) {
        adder.materialize(it.item, it.sourceId)
            ?.let { target -> NovelBrowseDialog.Migrate(currentId = duplicateId, targetId = target.id) }
    }

    // A novel is addressed by source and path, which only the raised dialog's duplicates still know.
    override fun duplicateScreen(entryId: Long) = (raised as? NovelBrowseDialog.AddDuplicate)
        ?.duplicates
        ?.firstOrNull { it.novel.id == entryId }
        ?.let { NovelScreen(it.novel.source, it.novel.url) }

    override fun NovelBrowseDialog.toNeutral(): EntryAddDialog = when (this) {
        is NovelBrowseDialog.RemoveNovel -> EntryAddDialog.Remove(item.name)
        is NovelBrowseDialog.ChangeCategory -> EntryAddDialog.ChangeCategory(initialSelection)
        is NovelBrowseDialog.AddDuplicate -> EntryAddDialog.AddDuplicate(
            duplicates = duplicates.map { it.toDuplicateCard(sourceLabels) },
            groupIdByEntryId = groupIdByNovelId,
            suggestGroup = suggestGroup,
        )
        is NovelBrowseDialog.Migrate -> EntryAddDialog.Migrate(currentId, targetId)
    }
}
