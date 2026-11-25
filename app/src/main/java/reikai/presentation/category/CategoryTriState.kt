package reikai.presentation.category

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import reikai.domain.category.CategoryContentType
import reikai.presentation.components.showsBothTypes
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource

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

/** Keeps the picks a dialog has not confirmed through a rotation or fold, which rebuilds it over the same inputs. */
val CategoryStatesSaver = listSaver<SnapshotStateMap<Long, TriState>, Long>(
    save = { states -> states.flatMap { (id, state) -> listOf(id, state.ordinal.toLong()) } },
    restore = { saved ->
        mutableStateMapOf<Long, TriState>().apply {
            saved.chunked(2).forEach { (id, state) -> this[id] = TriState.entries[state.toInt()] }
        }
    },
)

/**
 * One [TriStateItem] per category bound to [states]; a blank category name falls back to [defaultLabel].
 * A category restricted to one content type says so after its name, because one selection now spans
 * both libraries and picking a manga-only row while thinking about novels is otherwise invisible.
 * Universal rows carry no suffix: labelling every row would bury the ones that constrain something. While
 * Show leaves one type, no row carries one, as on Edit categories.
 */
@Composable
fun CategoryTriStateRows(
    categories: List<Category>,
    states: SnapshotStateMap<Long, TriState>,
    defaultLabel: String,
) {
    val labelled = showsBothTypes()
    categories.forEach { category ->
        val restricted = labelled && category.contentType != CategoryContentType.UNIVERSAL
        TriStateItem(
            label = category.name.ifBlank { defaultLabel },
            state = states[category.id] ?: TriState.DISABLED,
            onClick = { next -> states[category.id] = next },
            subtitle = stringResource(categoryContentTypeLabel(category.contentType)).takeIf { restricted },
        )
    }
}

/** Category ids currently set to [state] (e.g. [TriState.ENABLED_IS] for the included set). */
fun SnapshotStateMap<Long, TriState>.idsWith(state: TriState): Set<Long> =
    filterValues { it == state }.keys.toSet()
