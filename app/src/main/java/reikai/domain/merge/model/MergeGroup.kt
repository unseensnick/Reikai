package reikai.domain.merge.model

import androidx.compose.runtime.Immutable
import reikai.domain.library.ContentType

/**
 * A persisted merge group: the stable identity a set of same-series entries (across sources) share,
 * replacing the old per-call derivation from merge prefs plus a same-title scan.
 * A group is single-type ([contentType] is [ContentType.MANGA] or [ContentType.NOVELS], never
 * [ContentType.ALL]), with members in the matching per-type table. It owns its display overrides
 * and, when [overrideSourceRanking] is set, its own source ranking; otherwise the global
 * preferred-source list wins.
 */
@Immutable
data class MergeGroup(
    val id: Long,
    val contentType: ContentType,
    val titleOverride: String?,
    val coverOverride: String?,
    /** When true, the group's own member ordering wins; when false, the global ranking applies. */
    val overrideSourceRanking: Boolean,
)
