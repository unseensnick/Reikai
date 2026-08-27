package reikai.presentation.browse.catalogue

import kotlinx.coroutines.flow.StateFlow
import reikai.presentation.browse.EntryBrowseItemUi

/**
 * One paged result in a source's catalogue, neutral over content type. [key] is both the lazy-list
 * key and the selection identity, so nothing below this compares a manga id against a novel path.
 */
data class EntryBrowseRow(
    val key: String,
    val content: StateFlow<EntryBrowseRowContent>,
)

/**
 * What a row currently shows. This is a flow on the row because a favourite toggle has to re-render
 * the one cell without re-paging, and it carries the payload alongside the neutral data so a row
 * costs one collector rather than two. [payload] is the provider's own object, typed `Any` because
 * only a capability slot unwraps it, inside a branch that already knows the content type: a cast
 * anywhere else compiles cleanly and fails when a reader taps the row.
 */
data class EntryBrowseRowContent(
    val ui: EntryBrowseItemUi,
    val payload: Any,
)
