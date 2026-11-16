package reikai.presentation.reader

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.toShareIntent
import mihon.app.di.appGraph
import reikai.presentation.components.PictureViewerDialog
import reikai.presentation.components.savePictureOutliving
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/** A novel chapter's picture full screen, with a turn for a wide one, and Share and Save as a cover has. */
@Composable
fun ReaderPictureDialog(picture: ReaderDialog.Picture, onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val imageSaver = remember { context.appGraph.imageSaver }
    val appScope = remember { context.appGraph.appCoroutineScope }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun save(forSharing: Boolean) =
        scope.savePictureOutliving(appScope, context, imageSaver, picture.model, picture.name, forSharing) { uri ->
            when {
                uri == null -> snackbarHostState.showSnackbar(context.stringResource(MR.strings.error_saving_picture))
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
    )
}

/**
 * The file name a chapter's picture saves under. The picture's own source goes in, so two pictures of one
 * chapter do not save over each other, and saving one again finds the same file, as a manga page's number does.
 */
internal fun chapterPictureName(entryTitle: String?, chapterTitle: String?, model: Any): String {
    val suffix = " - " + model.toString().hashCode().toUInt().toString(16)
    return DiskUtil.buildValidFilename(
        listOfNotNull(entryTitle, chapterTitle).joinToString(" - "),
        DiskUtil.MAX_FILE_NAME_BYTES - suffix.byteSize(),
    ) + suffix
}
