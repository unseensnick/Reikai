package eu.kanade.tachiyomi.data.track.bangumi

import eu.kanade.tachiyomi.data.database.models.Track

fun Track.toApiStatus() = when (status) {
    Bangumi.PLAN_TO_READ -> 1
    Bangumi.COMPLETED -> 2
    // status can be 0 the first time a manga is tracked; treat it as reading.
    Bangumi.READING, 0 -> 3
    Bangumi.ON_HOLD -> 4
    Bangumi.DROPPED -> 5
    else -> throw NotImplementedError("Unknown status: $status")
}
