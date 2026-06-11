package reikai.domain.recommendation

/**
 * Sentinel source id for related-carousel entries that came from a tracker recommendation rather
 * than an installed source. Cards tagged with this id route through Global Search on click (the
 * tracker URL doesn't resolve to any installed extension), so the user can pick a source to read on.
 */
const val RECOMMENDS_SOURCE: Long = -1L
