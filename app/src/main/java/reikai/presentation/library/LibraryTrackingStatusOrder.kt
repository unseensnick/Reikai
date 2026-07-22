package reikai.presentation.library

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.track.Tracker

/**
 * Builds the `trackingStatusOrder` callback [LibraryDynamicGrouping.build] uses to sort BY_TRACK_STATUS
 * groups in reading-progress order (Reading first, Dropped last) instead of alphabetically. The order is
 * each logged-in tracker's own [Tracker.getStatusList] order, localized to the same display names the
 * grouping buckets by; a name from no tracker (e.g. "Not tracked") sorts last. Shared by the manga and
 * novel libraries so both order their track-status groups identically.
 *
 * Pure over its inputs (the [localize] lambda resolves a StringResource, injected so this stays testable).
 */
object LibraryTrackingStatusOrder {

    fun build(
        loggedInTrackers: List<Tracker>,
        localize: (StringResource) -> String,
    ): (String) -> String {
        val order = LinkedHashMap<String, Int>()
        for (tracker in loggedInTrackers) {
            for (code in tracker.getStatusList()) {
                val name = tracker.getStatus(code)?.let(localize) ?: continue
                if (name !in order) order[name] = order.size
            }
        }
        val fallback = order.size
        // Zero-padded so the kernel's case-insensitive string sort stays numeric (10 sorts after 9).
        return { name -> (order[name] ?: fallback).toString().padStart(4, '0') }
    }
}
