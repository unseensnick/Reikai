package reikai.domain.track

/**
 * Copies a merged group's trackers onto its members before a split, for either content type, so each
 * source keeps the binding afterwards. Only favourited members take part, a tracker whose remote id
 * disagrees across the group is skipped rather than guessed at, and a member is written only when it is
 * missing the group's furthest-read row or behind it, since a member left at chapter 5 would otherwise
 * push the remote service backwards after the split. All writes go in one batch.
 */
suspend fun <T> handOutGroupTrackers(
    enabled: Boolean,
    groupIds: List<Long>,
    isFavorite: suspend (Long) -> Boolean,
    tracksOf: suspend (Long) -> List<T>,
    trackerId: (T) -> Long,
    remoteId: (T) -> Long,
    lastChapterRead: (T) -> Double,
    copyTo: (T, Long) -> T,
    writeAll: suspend (List<T>) -> Unit,
) {
    if (!enabled || groupIds.size < 2) return
    val members = groupIds.filter { isFavorite(it) }
    if (members.size < 2) return

    val tracksByMember = members.associateWith { tracksOf(it) }
    val canonical = tracksByMember.values.flatten()
        .groupBy(trackerId)
        .filterValues { tracks -> tracks.mapTo(HashSet(), remoteId).size == 1 }
        .values.flatten()
        .let { canonicalTracksPerTracker(it, trackerId, lastChapterRead) }
    if (canonical.isEmpty()) return

    val toWrite = members.flatMap { memberId ->
        val own = tracksByMember.getValue(memberId).associateBy(trackerId)
        canonical.filter { lastChapterRead(it) > (own[trackerId(it)]?.let(lastChapterRead) ?: -1.0) }
            .map { copyTo(it, memberId) }
    }
    if (toWrite.isNotEmpty()) writeAll(toWrite)
}
