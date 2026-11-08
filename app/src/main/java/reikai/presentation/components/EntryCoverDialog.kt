package reikai.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.EditCoverAction
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Edit
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Full-cover dialog shared by manga and novels: a zoomable full-resolution cover with share / save /
 * set-custom-cover / delete-custom-cover actions. [cover] is a coil model (a `Manga` or a
 * `reikai.domain.novel.model.NovelCover`), so each content type feeds its own object. A null [onEditClick]
 * hides the edit menu: the entry cannot keep a custom cover, or the cover is a merged series' sibling.
 * Delete is offered in that menu only when [isCustomCover]. Replaces `MangaCoverDialog` / `NovelCoverDialog`.
 */
@Composable
fun EntryCoverDialog(
    cover: Any,
    isCustomCover: Boolean,
    snackbarHostState: SnackbarHostState,
    onShareClick: () -> Unit,
    onSaveClick: () -> Unit,
    onEditClick: ((EditCoverAction) -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    PictureViewerDialog(
        picture = cover,
        snackbarHostState = snackbarHostState,
        onShareClick = onShareClick,
        onSaveClick = onSaveClick,
        onDismissRequest = onDismissRequest,
    ) {
        if (onEditClick != null) {
            Box {
                var expanded by remember { mutableStateOf(false) }
                IconButton(
                    onClick = {
                        if (isCustomCover) {
                            expanded = true
                        } else {
                            onEditClick(EditCoverAction.EDIT)
                        }
                    },
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Edit,
                        contentDescription = stringResource(MR.strings.action_edit_cover),
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    offset = DpOffset(8.dp, 0.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_edit)) },
                        onClick = {
                            onEditClick(EditCoverAction.EDIT)
                            expanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_delete)) },
                        onClick = {
                            onEditClick(EditCoverAction.DELETE)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
