package reikai.domain.source.model

import androidx.compose.runtime.Immutable
import reikai.domain.source.SourceKey

/**
 * One row of a feed: a source, and optionally the saved search to run against it. With no
 * [savedSearchId] the row shows that source's Latest, falling back to Popular where it has none.
 *
 * [global] separates the two feeds a row can belong to: the Browse tab's feed, which mixes sources,
 * and a single source's own feed. A row is in one or the other, never both.
 */
@Immutable
data class FeedSavedSearch(
    val id: Long,
    val sourceKey: SourceKey,
    val savedSearchId: Long?,
    val global: Boolean,
    val feedOrder: Long,
)
