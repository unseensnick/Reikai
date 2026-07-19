package reikai.presentation.details

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.util.Screen
import reikai.presentation.components.EntryCoverDialog
import reikai.presentation.components.ManageMergeSourceRow
import reikai.presentation.components.ManageMergeSourcesDialog
import reikai.presentation.track.EntryTrackInfoDialogHomeScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * The details dialogs that are genuinely shared by both content types: same composable, same neutral data,
 * so a change reaches manga and novels at once. Each screen maps its own model's dialog into this and hands
 * it to [EntryDetailsDialogHost]; the per-type dialogs (change-category, duplicate, chapter-settings, migrate,
 * fetch-interval, page-selector, ...) stay in each screen's own dispatcher, where their data genuinely diverges.
 */
sealed interface EntryDetailsDialog {
    /** Edit custom info. Save / reset / tracker-autofill route through the behaviour; only the seed values
     *  and the per-type cover-model factory (for the live preview) are carried. */
    data class EditInfo(
        val initial: EntryEditInfoUi,
        val source: EntryEditInfoUi,
        val seedColor: Color?,
        val coverModel: (String) -> Any,
    ) : EntryDetailsDialog

    /** Full cover viewer; the per-type cover ScreenModel is resolved via [EntryDetailsBehavior.createCoverScreenModel]. */
    data object Cover : EntryDetailsDialog

    data class ManageSources(
        val sources: List<EntryManageSourceInfo>,
        val isOverridden: Boolean,
    ) : EntryDetailsDialog

    data class TrackSheet(
        val entryId: Long,
        val entryTitle: String,
        val sourceId: Long?,
        val isNovel: Boolean,
    ) : EntryDetailsDialog

    /** Confirm a bulk chapter delete; [chapterIds] are the rows captured when the dialog opened. */
    data class DeleteChapters(val chapterIds: List<Long>) : EntryDetailsDialog
}

/** One merge source for the manage-sources dialog: id + name + chapter count (for the coverage subtitle). */
data class EntryManageSourceInfo(val id: Long, val sourceName: String, val chapterCount: Int)

/**
 * Renders the shared details dialogs from the neutral [EntryDetailsDialog], driving every action through
 * [behavior]. A `Screen` extension because the cover viewer resolves a ScreenModel (needs the Screen receiver).
 * The caller also runs its own per-type dispatcher for the dialogs that stay type-specific; the two never
 * overlap (a shared dialog maps here and the caller's `when` skips it, and vice versa).
 */
@Composable
fun Screen.EntryDetailsDialogHost(
    dialog: EntryDetailsDialog?,
    behavior: EntryDetailsBehavior,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    when (dialog) {
        null -> {}
        is EntryDetailsDialog.EditInfo -> EntryEditInfoDialog(
            initial = dialog.initial,
            source = dialog.source,
            seedColor = dialog.seedColor,
            coverModel = dialog.coverModel,
            onDismissRequest = onDismissRequest,
            onSave = behavior::saveInfo,
            onResetAll = behavior::resetInfo,
            autofill = TrackerAutofill(
                candidates = behavior::autofillCandidates,
                fetch = behavior::fetchTrackerMetadata,
            ),
        )
        EntryDetailsDialog.Cover -> {
            val coverSm = rememberScreenModel { behavior.createCoverScreenModel() }
            val cover by coverSm.coverModel.collectAsState()
            if (cover != null) {
                val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) coverSm.editCover(context, uri)
                }
                EntryCoverDialog(
                    cover = cover!!,
                    isCustomCover = remember(cover) { coverSm.hasCustomCover() },
                    snackbarHostState = coverSm.snackbarHostState,
                    onShareClick = { coverSm.shareCover(context) },
                    onSaveClick = { coverSm.saveCover(context) },
                    onEditClick = {
                        when (it) {
                            EditCoverAction.EDIT -> getContent.launch("image/*")
                            EditCoverAction.DELETE -> coverSm.deleteCustomCover(context)
                        }
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
            onReorder = behavior::reorderSources,
            onResetOrder = behavior::resetSourceOrder,
            onSplit = behavior::splitSources,
            onRemoveFromLibrary = behavior::removeSourcesFromLibrary,
            onRemoveAll = behavior::removeAllSourcesFromLibrary,
        )
        is EntryDetailsDialog.TrackSheet -> {
            // Remember the screen so the merge collectors' frequent recompositions don't rebuild it and reset
            // its navigator mid-write (the manga side hit an InsertTrack JobCancellationException here).
            val trackScreen = remember(dialog.entryId, dialog.sourceId) {
                EntryTrackInfoDialogHomeScreen(
                    entryId = dialog.entryId,
                    entryTitle = dialog.entryTitle,
                    sourceId = dialog.sourceId,
                    isNovel = dialog.isNovel,
                )
            }
            NavigatorAdaptiveSheet(
                screen = trackScreen,
                enableSwipeDismiss = { it.lastItem is EntryTrackInfoDialogHomeScreen },
                onDismissRequest = onDismissRequest,
            )
        }
        is EntryDetailsDialog.DeleteChapters -> DeleteChaptersDialog(
            onDismissRequest = onDismissRequest,
            onConfirm = { behavior.deleteChapters(dialog.chapterIds) },
        )
    }
}
