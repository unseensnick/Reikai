package eu.kanade.presentation.library

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun DeleteLibraryMangaDialog(
    containsLocalManga: Boolean,
    // RK: total grouped sources behind the selection when it contains a merged cover (else 0). When
    //     set, an opt-in checkbox widens the removal from the primary to all N grouped sources.
    groupedSourceCount: Int = 0,
    onDismissRequest: () -> Unit,
    onConfirm: (Boolean, Boolean, Boolean) -> Unit,
) {
    var list by remember {
        mutableStateOf(
            buildList<CheckboxState.State<StringResource>> {
                add(CheckboxState.State.None(MR.strings.manga_from_library))
                if (!containsLocalManga) {
                    add(CheckboxState.State.None(MR.strings.downloaded_chapters))
                }
            },
        )
    }
    // RK: apply the removal to every source in a merged group, not just the primary cover. Defaults
    //     on for a merged selection, since removing only the primary leaves the other sources
    //     favorited but collapsed out of view, so the entry appears to half-vanish. Still a checkbox
    //     rather than automatic, because the removal is destructive.
    val showGroupedOption = groupedSourceCount > 0
    var removeGrouped by remember { mutableStateOf(showGroupedOption) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = list.any { it.isChecked },
                onClick = {
                    onDismissRequest()
                    onConfirm(
                        list[0].isChecked,
                        list.getOrElse(1) { CheckboxState.State.None(0) }.isChecked,
                        showGroupedOption && removeGrouped,
                    )
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_remove))
        },
        text = {
            Column {
                list.forEach { state ->
                    LabeledCheckbox(
                        label = stringResource(state.value),
                        checked = state.isChecked,
                        onCheckedChange = {
                            val index = list.indexOf(state)
                            if (index != -1) {
                                val mutableList = list.toMutableList()
                                mutableList[index] = state.next() as CheckboxState.State<StringResource>
                                list = mutableList.toList()
                            }
                        },
                    )
                }
                // RK -->
                if (showGroupedOption) {
                    LabeledCheckbox(
                        label = pluralStringResource(
                            MR.plurals.action_remove_grouped_sources,
                            groupedSourceCount,
                            groupedSourceCount,
                        ),
                        checked = removeGrouped,
                        onCheckedChange = { removeGrouped = it },
                    )
                }
                // RK <--
            }
        },
    )
}
