package reikai.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.pluralStringResource

/**
 * The "All grouped sources" choice a removal offers for a merged series, in the library's Remove dialog
 * and the details heart's. Ticked by default: removing one source leaves the others favorited but
 * collapsed out of view, so the series appears to half-vanish. Still a choice, since removal is
 * destructive. A [groupedSourceCount] of 0 offers nothing and never widens.
 */
@Stable
class GroupedSourcesChoice(val groupedSourceCount: Int) {
    var isChecked by mutableStateOf(groupedSourceCount > 0)

    /** Whether the removal widens to every grouped source. */
    val removesGrouped: Boolean get() = groupedSourceCount > 0 && isChecked
}

@Composable
fun rememberGroupedSourcesChoice(groupedSourceCount: Int): GroupedSourcesChoice =
    remember(groupedSourceCount) { GroupedSourcesChoice(groupedSourceCount) }

@Composable
fun GroupedSourcesCheckbox(choice: GroupedSourcesChoice) {
    if (choice.groupedSourceCount == 0) return
    LabeledCheckbox(
        label = pluralStringResource(
            MR.plurals.action_remove_grouped_sources,
            choice.groupedSourceCount,
            choice.groupedSourceCount,
        ),
        checked = choice.isChecked,
        onCheckedChange = { choice.isChecked = it },
    )
}
