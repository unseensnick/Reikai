package reikai.presentation.details

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.manga.components.SetIntervalDialog
import reikai.data.coil.withCoverFallbacks
import reikai.domain.chapter.ChapterNumberEdit
import reikai.domain.entry.EntryCustomInfo
import reikai.domain.entry.EntryId
import reikai.domain.merge.DetailsRemoval
import reikai.presentation.components.EntryCoverDialog
import reikai.presentation.components.ManageMergeSourceRow
import reikai.presentation.components.ManageMergeSourcesDialog
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import kotlin.time.Instant

/**
 * The details dialogs that are genuinely shared by both content types: same composable, same neutral data,
 * so a change reaches manga and novels at once. Each screen maps its own model's dialog into this and hands
 * it to [EntryDetailsDialogHost]; the per-type dialogs (change-category, duplicate, chapter-settings, migrate,
 * page-selector, ...) stay in each screen's own dispatcher, where their data genuinely diverges.
 */
sealed interface EntryDetailsDialog {
    /** Edit custom info. Save / reset / tracker-autofill route through the behaviour; only the seed values
     *  and the per-type cover-model factory (for the live preview) are carried. */
    data class EditInfo(
        val initial: EntryEditInfoUi,
        val source: EntryEditInfoUi,
        val coverModel: (String) -> Any,
    ) : EntryDetailsDialog

    /**
     * Full cover viewer; the per-type cover ViewModel is resolved via [EntryDetailsBehavior.createCoverViewModel].
     * A picked local [volume] shows that volume's own cover.
     */
    data class Cover(val volume: EntryChapterListItem.Volume?) : EntryDetailsDialog

    data class ManageSources(
        val sources: List<EntryManageSourceInfo>,
        val isOverridden: Boolean,
    ) : EntryDetailsDialog

    /** Confirm a bulk chapter delete; [chapterIds] are the rows captured when the dialog opened. */
    data class DeleteChapters(val chapterIds: List<Long>) : EntryDetailsDialog

    /** Smart update's prediction for the entry: its [interval] in days (negative when the user set it) and
     *  when it is next due. [editable] is whether the release-period restriction is on, as the choice needs. */
    data class SetFetchInterval(val interval: Int, val nextUpdate: Instant?, val editable: Boolean) : EntryDetailsDialog

    /** Confirm clearing downloads, [target] captured when it opened. */
    data class ClearDownloads(val target: ClearDownloadsTarget) : EntryDetailsDialog

    /** The heart's remove confirm for [entryTitle], [removal] captured when it opened. */
    data class RemoveFromLibrary(val removal: DetailsRemoval, val entryTitle: String) : EntryDetailsDialog

    /** Correct one chapter's number, [edit] read when it opened. */
    data class ChapterNumber(val edit: ChapterNumberEdit) : EntryDetailsDialog

    /** A local series whose folder is gone, and the folders holding its files the user may link it to. */
    data class RelinkSeries(val entryTitle: String, val candidates: List<String>) : EntryDetailsDialog
}

/**
 * The Edit info dialog over [entry], one seed rule for both types: the form opens on the overlaid values
 * with the stored alternative titles, and a save diffs against the raw entry, which carries none.
 */
fun <T> editInfoDialog(
    entry: T,
    custom: EntryCustomInfo?,
    overlay: T.(EntryCustomInfo?) -> T,
    toUi: T.(altTitles: List<String>) -> EntryEditInfoUi,
    coverModel: (String) -> Any,
) = EntryDetailsDialog.EditInfo(
    initial = entry.overlay(custom).toUi(custom?.altTitles.orEmpty()),
    source = entry.toUi(emptyList()),
    coverModel = coverModel,
)

/** One merge source for the manage-sources dialog: id + name + chapter count (for the coverage subtitle). */
data class EntryManageSourceInfo(val id: Long, val sourceName: String, val chapterCount: Int, val isLocal: Boolean)

/**
 * Renders the shared details dialogs from the neutral [EntryDetailsDialog], driving every action through
 * [behavior].
 * The caller also runs its own per-type dispatcher for the dialogs that stay type-specific; the two never
 * overlap (a shared dialog maps here and the caller's `when` skips it, and vice versa).
 */
@Composable
fun EntryDetailsDialogHost(
    dialog: EntryDetailsDialog?,
    behavior: EntryDetailsBehavior,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    when (dialog) {
        null -> {}
        is EntryDetailsDialog.EditInfo -> {
            val state by behavior.state.collectAsStateWithLifecycle()
            val loaded = state as? EntryDetailsScreenState.Loaded
            val canOrganize =
                behavior.rememberCanOrganize(loaded?.entryId, loaded?.ownsLocalFiles == true, null) == true
            EntryEditInfoDialog(
                initial = dialog.initial,
                source = dialog.source,
                coverModel = dialog.coverModel,
                onDismissRequest = onDismissRequest,
                onSave = { edited, renameFiles ->
                    behavior.saveInfo(edited)
                    if (renameFiles && loaded != null) {
                        behavior.volumePicking.requestOrganize(loaded.entryId, edited.title)
                    }
                },
                canRenameFiles = canOrganize,
                onResetAll = behavior::resetInfo,
                autofill = TrackerAutofill(
                    candidates = behavior::autofillCandidates,
                    fetch = behavior::fetchTrackerMetadata,
                ),
                mergedTags = behavior::mergedSourceTags,
            )
        }
        is EntryDetailsDialog.Cover -> {
            // The behavior returns the per-type subclass through the star-projected base, which AndroidX's
            // default factory would try to instantiate reflectively, so the construction moves into an
            // explicit initializer. An initializer
            // freezes the entry it captures, and both adapters build for the source chip's entry, so
            // the model is keyed by that entry: without the key the store would hand back the model
            // built for whichever chip opened the dialog first.
            val coverFactory = remember(behavior) {
                viewModelFactory { initializer { behavior.createCoverViewModel() } }
            }
            val coverViewModel = viewModel<EntryCoverViewModel<*>>(
                key = behavior.coverKey(),
                factory = coverFactory,
            )
            val cover by coverViewModel.coverModel.collectAsStateWithLifecycle()
            val isCoverEditable by coverViewModel.isCoverEditable.collectAsStateWithLifecycle()
            val page by behavior.state.collectAsStateWithLifecycle()
            val isCoverAnchored = (page as? EntryDetailsScreenState.Loaded)?.isCoverAnchored == true
            // The header's fallbacks, so the viewer, Save and Share show the cover the header drew.
            val coverFallbacks = (page as? EntryDetailsScreenState.Loaded)?.details?.header?.coverFallbacks.orEmpty()
            // A picked volume shows its own cover, the one its header draws; one with none shows the series'.
            val volume = dialog.volume
            val type = (page as? EntryDetailsScreenState.Loaded)?.entryId?.contentType
            val volumeCover by produceState<Any?>(if (volume == null) null else VolumeCoverLoading, volume, type) {
                value =
                    if (volume == null ||
                        type == null
                    ) {
                        null
                    } else {
                        behavior.volumePicking.cover(type, volume.series, volume.key)
                    }
            }
            if (cover != null && volumeCover !== VolumeCoverLoading) {
                val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) coverViewModel.editCover(context, uri)
                }
                val shownCover = remember(cover, coverFallbacks, volumeCover) {
                    volumeCover ?: withCoverFallbacks(cover!!, coverFallbacks)
                }
                EntryCoverDialog(
                    cover = shownCover,
                    isCustomCover = remember(cover, volume) { volume == null && coverViewModel.hasCustomCover() },
                    snackbarHostState = coverViewModel.snackbarHostState,
                    onShareClick = { coverViewModel.shareCover(context, shownCover) },
                    onSaveClick = { coverViewModel.saveCover(context, shownCover) },
                    // Null hides Edit and Delete: for an entry that cannot keep a custom cover, for
                    // a chip's sibling, since a custom cover has to land on the entry the library
                    // renders, so it is only offered where the group's own cover is the one on screen,
                    // and for a picked volume, whose cover is its book's, not the series' they write.
                    onEditClick = if (volume == null && isCoverEditable && isCoverAnchored) {
                        { action: EditCoverAction ->
                            when (action) {
                                EditCoverAction.EDIT -> getContent.launch("image/*")
                                EditCoverAction.DELETE -> coverViewModel.deleteCustomCover(context)
                            }
                        }
                    } else {
                        null
                    },
                    onDismissRequest = onDismissRequest,
                )
            } else {
                LoadingScreen(Modifier.systemBarsPadding())
            }
        }
        is EntryDetailsDialog.ManageSources -> ManageMergeSourcesDialog(
            sources = dialog.sources.map {
                ManageMergeSourceRow(
                    id = it.id,
                    sourceName = it.sourceName,
                    subtitle = pluralStringResource(MR.plurals.manga_num_chapters, it.chapterCount, it.chapterCount),
                )
            },
            isOverridden = dialog.isOverridden,
            onDismissRequest = onDismissRequest,
            onReorder = behavior.mergeActions::reorderSources,
            onResetOrder = behavior.mergeActions::resetSourceOrder,
            onSplit = behavior.mergeActions::splitSources,
            onRemoveFromLibrary = behavior.mergeActions::removeSourcesFromLibrary,
            onRemoveAll = behavior.mergeActions::removeAllSourcesFromLibrary,
            onCombineVolumes = behavior.mergeActions::combineAsVolumes.takeIf { dialog.sources.all { it.isLocal } },
        )
        is EntryDetailsDialog.DeleteChapters -> DeleteChaptersDialog(
            onDismissRequest = onDismissRequest,
            onConfirm = { behavior.deleteChapters(dialog.chapterIds) },
        )
        is EntryDetailsDialog.SetFetchInterval -> SetIntervalDialog(
            interval = dialog.interval,
            nextUpdate = dialog.nextUpdate,
            onDismissRequest = onDismissRequest,
            onValueChanged = behavior::setFetchInterval.takeIf { dialog.editable },
        )
        is EntryDetailsDialog.ClearDownloads -> ClearDownloadsDialog(
            sourceName = dialog.target.sourceName,
            onDismissRequest = onDismissRequest,
            onConfirm = { behavior.clearDownloads(dialog.target.ids) },
        )
        is EntryDetailsDialog.RemoveFromLibrary -> RemoveFromLibraryDialog(
            entryTitle = dialog.entryTitle,
            groupedSourceCount = if (dialog.removal.offersGroup) dialog.removal.groupIds.size else 0,
            onDismissRequest = onDismissRequest,
            onConfirm = { behavior.removeFromLibrary(dialog.removal.targets(it)) },
        )
        is EntryDetailsDialog.ChapterNumber -> ChapterNumberDialog(
            edit = dialog.edit,
            onDismissRequest = onDismissRequest,
            onSave = { behavior.saveChapterNumber(dialog.edit, it) },
        )
        is EntryDetailsDialog.RelinkSeries -> RelinkSeriesDialog(
            entryTitle = dialog.entryTitle,
            candidates = dialog.candidates,
            onDismissRequest = onDismissRequest,
            onConfirm = behavior::relinkSeries,
        )
    }
}

/** The viewer's volume cover before its file has been read, told apart from a volume that has none. */
private object VolumeCoverLoading
