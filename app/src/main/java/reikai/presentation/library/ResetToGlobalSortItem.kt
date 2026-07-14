package reikai.presentation.library

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BaseSortItem
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The "Reset to global sort" action in the library Sort tab, shown only when a category has a
 * per-category sort override. A divider plus a restore icon set it apart from the sort-mode rows above,
 * so it reads as an action rather than another sort option. Shared by the manga + novel Sort dialogs.
 */
@Composable
fun ResetToGlobalSortItem(onClick: () -> Unit) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    BaseSortItem(
        label = stringResource(MR.strings.action_sort_reset_to_global),
        icon = Icons.Outlined.SettingsBackupRestore,
        onClick = onClick,
    )
}
