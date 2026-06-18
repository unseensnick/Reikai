package reikai.presentation.category

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.components.TriStateItem

/**
 * Per-category include/exclude tri-state map (checked = include, inverted = exclude, blank = ignore),
 * keyed by category id. Built once per (categories, included, excluded) by the shared
 * [CategoryFilterRow]; the caller wraps it in `remember`.
 */
fun categoryStatesOf(
    categories: List<Category>,
    included: Set<Long>,
    excluded: Set<Long>,
): SnapshotStateMap<Long, TriState> =
    mutableStateMapOf<Long, TriState>().apply {
        categories.forEach { category ->
            this[category.id] = when (category.id) {
                in included -> TriState.ENABLED_IS
                in excluded -> TriState.ENABLED_NOT
                else -> TriState.DISABLED
            }
        }
    }

/** One [TriStateItem] per category bound to [states]; a blank category name falls back to [defaultLabel]. */
@Composable
fun CategoryTriStateRows(
    categories: List<Category>,
    states: SnapshotStateMap<Long, TriState>,
    defaultLabel: String,
) {
    categories.forEach { category ->
        TriStateItem(
            label = category.name.ifBlank { defaultLabel },
            state = states[category.id] ?: TriState.DISABLED,
            onClick = { next -> states[category.id] = next },
        )
    }
}

/** Category ids currently set to [state] (e.g. [TriState.ENABLED_IS] for the included set). */
fun SnapshotStateMap<Long, TriState>.idsWith(state: TriState): Set<Long> =
    filterValues { it == state }.keys.toSet()

/** Parse a string-id pref set into category ids, dropping any non-numeric entry. */
fun Set<String>.toLongIdSet(): Set<Long> = mapNotNull(String::toLongOrNull).toSet()
