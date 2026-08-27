package reikai.presentation.browse.source

import kotlinx.coroutines.flow.StateFlow
import reikai.presentation.browse.EntryBrowseItemUi

/**
 * One paged browse result, neutral over content type. [ui] is a flow so a favourite toggle re-renders
 * the one cell without re-paging, which is how the manga side already works. [key] is both the
 * lazy-list key and the selection identity, so nothing below this compares a manga id against a novel
 * path. [payload] is the provider's own object, typed `Any` because only a capability slot unwraps it
 * inside a branch that already knows the type: a cast anywhere else compiles and fails on tap.
 */
data class EntryBrowseRow(
    val key: String,
    val ui: StateFlow<EntryBrowseItemUi>,
    val payload: Any,
)
