package reikai.domain.library

import tachiyomi.core.common.preference.TriState

/**
 * The custom-interval filter as a library applies it: only while that type's update restrictions skip
 * series outside their release period, since the interval means nothing otherwise. Both libraries call it.
 */
fun effectiveIntervalFilter(skipsOutsideReleasePeriod: Boolean, filter: TriState): TriState =
    if (skipsOutsideReleasePeriod) filter else TriState.DISABLED
