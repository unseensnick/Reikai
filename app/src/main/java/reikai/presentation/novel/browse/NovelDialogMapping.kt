package reikai.presentation.novel.browse

import reikai.presentation.browse.catalogue.EntryBrowseDialog
import reikai.presentation.browse.components.toDuplicateCard

/**
 * The novel add flow's own dialogs as the shared layer sees them. One mapping rather than one per
 * surface: every screen that long-presses a novel result asks the same four questions, and only the
 * novel half knows what its payloads look like.
 *
 * The raw dialog is what answers a confirm, since it carries the item and source the neutral form
 * drops, so a caller keeps it beside the mapped one.
 */
fun NovelBrowseDialog.toNeutral(): EntryBrowseDialog = when (this) {
    is NovelBrowseDialog.RemoveNovel -> EntryBrowseDialog.Remove(item.name)
    is NovelBrowseDialog.ChangeCategory -> EntryBrowseDialog.ChangeCategory(initialSelection)
    is NovelBrowseDialog.AddDuplicate -> EntryBrowseDialog.AddDuplicate(
        duplicates = duplicates.map { it.toDuplicateCard(sourceLabels, sourceSites) },
        groupIdByEntryId = groupIdByNovelId,
        suggestGroup = suggestGroup,
    )
    is NovelBrowseDialog.Migrate -> EntryBrowseDialog.Migrate(currentId, targetId)
}
