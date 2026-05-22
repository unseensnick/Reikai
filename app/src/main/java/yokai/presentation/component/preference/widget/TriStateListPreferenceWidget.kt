package yokai.presentation.component.preference.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import yokai.presentation.component.preference.Preference

@Composable
fun TriStateListPreferenceWidget(
    preference: Preference.PreferenceItem.TriStateListPreference,
    included: Set<String>,
    excluded: Set<String>,
    onValuesChanged: (included: Set<String>, excluded: Set<String>) -> Unit,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = preference.title,
        subtitle = preference.subtitleProvider(included, excluded, preference.entries),
        icon = preference.icon,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        val items = remember(preference.entries) { preference.entries.keys.toList() }
        TriStateListDialog(
            title = preference.title,
            items = items,
            initialChecked = items.filter { it in included },
            initialInversed = items.filter { it in excluded },
            itemLabel = { preference.entries[it].orEmpty() },
            onDismissRequest = { isDialogShown = false },
            onValueChanged = { newIncluded, newExcluded ->
                onValuesChanged(newIncluded.toSet(), newExcluded.toSet())
                isDialogShown = false
            },
        )
    }
}
