package reikai.presentation.reader

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.reader.SetCoverDialog
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.Photo
import reikai.presentation.components.PictureViewerDialog
import reikai.presentation.components.copyPictureToClipboard
import reikai.presentation.components.pictureBytes
import reikai.presentation.components.savePictureOutliving
import reikai.storage.validFileName
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A novel chapter's picture full screen, with a turn for a wide one, and a manga page's long-press verbs:
 * Share, Save, Copy to clipboard and Set as cover.
 */
@Composable
fun ReaderPictureDialog(picture: ReaderDialog.Picture, onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val imageSaver = remember { context.appGraph.imageSaver }
    val appScope = remember { context.appGraph.appCoroutineScope }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmingCover by remember { mutableStateOf(false) }
    fun save(forSharing: Boolean, copy: Boolean = false) =
        scope.savePictureOutliving(
            appScope,
            context,
            imageSaver,
            picture.model,
            picture.name,
            picture.folder,
            forSharing,
        ) { uri ->
            when {
                uri == null -> snackbarHostState.showSnackbar(context.stringResource(MR.strings.error_saving_picture))
                copy -> context.copyPictureToClipboard(uri)
                forSharing -> context.startActivity(uri.toShareIntent(context))
                else -> snackbarHostState.showSnackbar(context.stringResource(MR.strings.picture_saved))
            }
        }
    PictureViewerDialog(
        picture = picture.model,
        snackbarHostState = snackbarHostState,
        onShareClick = { save(forSharing = true) },
        onSaveClick = { save(forSharing = false) },
        onDismissRequest = onDismissRequest,
        canRotate = true,
    ) {
        IconButton(onClick = { save(forSharing = true, copy = true) }) {
            Icon(MaterialSymbols.Rounded.ContentCopy, stringResource(MR.strings.action_copy_to_clipboard))
        }
        IconButton(onClick = { confirmingCover = true }) {
            Icon(MaterialSymbols.Rounded.Photo, stringResource(MR.strings.set_as_cover))
        }
    }
    if (confirmingCover) {
        SetCoverDialog(
            onConfirm = {
                confirmingCover = false
                scope.launch {
                    val result = picture.cover.setAsCover { pictureBytes(context, picture.model) }
                    snackbarHostState.showSnackbar(context.stringResource(setAsCoverMessage(result)))
                }
            },
            onDismiss = { confirmingCover = false },
        )
    }
}

/** What a Set as cover says once it has run, the manga reader's answers. */
internal fun setAsCoverMessage(result: SetAsCoverResult) = when (result) {
    SetAsCoverResult.Success -> MR.strings.cover_updated
    SetAsCoverResult.AddToLibraryFirst -> MR.strings.notification_first_add_to_library
    SetAsCoverResult.Error -> MR.strings.notification_cover_update_failed
}

/**
 * The file name a chapter's picture saves under. The picture's own source goes in, so two pictures of one
 * chapter do not save over each other, and saving one again finds the same file, as a manga page's number does.
 */
internal fun chapterPictureName(entryTitle: String?, chapterTitle: String?, model: Any): String {
    val suffix = " - " + model.toString().hashCode().toUInt().toString(16)
    return validFileName(listOfNotNull(entryTitle, chapterTitle).joinToString(" - "), suffix)
}
