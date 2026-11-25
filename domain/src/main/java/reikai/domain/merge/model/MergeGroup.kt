package reikai.domain.merge.model

import androidx.compose.runtime.Immutable
import reikai.domain.library.ContentType

/**
 * A persisted merge group: the stable identity a set of same-series entries (across sources) share.
 * A group is single-type ([contentType] is [ContentType.MANGA] or [ContentType.NOVELS], never
 * [ContentType.ALL]), with members in the matching per-type table. When [overrideSourceRanking] is
 * set it owns its source ranking; otherwise the global preferred-source list wins.
 */
@Immutable
data class MergeGroup(
    val id: Long,
    val contentType: ContentType,
    val overrideSourceRanking: Boolean,
)
