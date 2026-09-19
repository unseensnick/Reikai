package reikai.domain.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Chapters each downloader finished per series while that series stayed queued. A queue card's total
 * is what remains plus this, so it survives the queue screen closing, and a cancelled chapter shrinks
 * the total instead of reading as downloaded. Both downloaders own one and call it at their own
 * completion and removal sites; neither may clear it on a reorder, which empties the queue in passing.
 */
class SeriesCompletions {

    private val mutableCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val counts: StateFlow<Map<Long, Int>> = mutableCounts.asStateFlow()

    fun record(seriesId: Long) {
        mutableCounts.update { it + (seriesId to (it[seriesId] ?: 0) + 1) }
    }

    /** Forget every series no longer in the queue, so one queued again later starts from zero. */
    fun retainOnly(queuedSeriesIds: Set<Long>) {
        mutableCounts.update { counts -> counts.filterKeys { it in queuedSeriesIds } }
    }

    fun clear() {
        mutableCounts.value = emptyMap()
    }
}
