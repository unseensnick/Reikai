package reikai.domain.source.model

import androidx.compose.runtime.Immutable
import reikai.domain.source.SourceKey

/**
 * A named browse filter preset for one source, of either content type.
 *
 * [filtersJson] is opaque at this level: the manga side stores an encoded `FilterList`, the novel side
 * its filter-value map, and only that content type's reader can interpret it. A null means the search
 * carries no filters, which is not the same as an empty set the source could not decode.
 */
@Immutable
data class SavedSearch(
    val id: Long,
    val sourceKey: SourceKey,
    val name: String,
    val query: String?,
    val filtersJson: String?,
)
