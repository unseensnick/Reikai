package yokai.presentation.settings.screen.advanced

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.icerock.moko.resources.compose.stringResource
import kotlin.coroutines.resume
import yokai.domain.DialogHostState
import yokai.i18n.MR
import yokai.presentation.component.LabeledCheckbox
import android.R as AR

data class CleanupChaptersOptions(
    val deleteRead: Boolean,
    val deleteNonFavorite: Boolean,
)

suspend fun DialogHostState.awaitCleanupDownloadedChapters(): CleanupChaptersOptions? = dialog { cont ->
    var deleteRead by rememberSaveable { mutableStateOf(true) }
    var deleteNonFavorite by rememberSaveable { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = { cont.resume(null) },
        title = { Text(text = stringResource(MR.strings.clean_up_downloaded_chapters)) },
        text = {
            Box {
                val state = rememberLazyListState()
                LazyColumn(state = state) {
                    item {
                        LabeledCheckbox(
                            label = stringResource(MR.strings.clean_orphaned_downloads),
                            checked = true,
                            onCheckedChange = {},
                            enabled = false,
                        )
                    }
                    item {
                        LabeledCheckbox(
                            label = stringResource(MR.strings.clean_read_downloads),
                            checked = deleteRead,
                            onCheckedChange = { deleteRead = it },
                        )
                    }
                    item {
                        LabeledCheckbox(
                            label = stringResource(MR.strings.clean_read_manga_not_in_library),
                            checked = deleteNonFavorite,
                            onCheckedChange = { deleteNonFavorite = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                cont.resume(CleanupChaptersOptions(deleteRead = deleteRead, deleteNonFavorite = deleteNonFavorite))
            }) {
                Text(stringResource(AR.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { cont.resume(null) }) {
                Text(stringResource(AR.string.cancel))
            }
        },
    )
}
