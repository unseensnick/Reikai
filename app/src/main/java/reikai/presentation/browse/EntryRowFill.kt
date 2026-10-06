package reikai.presentation.browse

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import reikai.presentation.browse.catalogue.EntryBrowseRow
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.util.runCatchingCancellable

/** How many sources one limiter lets run at once, for every search that fans out over sources. */
const val SOURCE_SEARCH_CONCURRENCY = 5

/**
 * Runs [load] for every item, as many at once as its [permits] allow, and hands each result to
 * [onResult] the moment it lands. One item failing costs that item alone.
 *
 * A result that lands after this call was cancelled is dropped, so a superseded pass cannot write
 * over the one that replaced it: cancelling cannot stop a load already past its last suspension.
 */
suspend fun <T, R> fanOutPerSource(
    items: List<T>,
    permits: (T) -> Semaphore,
    load: suspend (T) -> R,
    onResult: (T, Result<R>) -> Unit,
): Unit = coroutineScope {
    items.forEach { item ->
        launch {
            val result = permits(item).withPermit { runCatchingCancellable { load(item) } }
            if (isActive) onResult(item, result)
        }
    }
}

/**
 * Fills every still-loading row of [rows] by running [load] against it, a few per [group] at a time,
 * and writing each result the moment it lands rather than when the slowest finishes.
 *
 * Shared because this shape is got wrong the same three ways every time: one limiter across all
 * groups, a read outside the write, and a superseded pass writing over its replacement.
 */
suspend fun fillEntryRows(
    rows: List<BrowseSearchRow>,
    group: (BrowseSearchRow) -> Any,
    /** Re-applied as each row lands, so a row that answers can move. Null keeps the given order. */
    order: Comparator<BrowseSearchRow>? = null,
    concurrency: Int = SOURCE_SEARCH_CONCURRENCY,
    /** Applies a change to the current rows. The caller supplies this so the read and the write stay
     *  inside one state update; reading outside would let two results race onto one snapshot. */
    updateRows: ((List<BrowseSearchRow>) -> List<BrowseSearchRow>) -> Unit,
    load: suspend (BrowseSearchRow) -> List<EntryBrowseRow>,
) {
    val semaphores = rows.map(group).distinct().associateWith { Semaphore(concurrency) }
    fanOutPerSource(
        items = rows.filter { it.state is EntrySearchState.Loading },
        permits = { semaphores.getValue(group(it)) },
        load = load,
    ) { row, result ->
        val state = result.fold({ EntrySearchState.Success(it) }, { EntrySearchState.Error(it) })
        updateRows { current ->
            current
                .map { if (it.id == row.id) it.copy(state = state) else it }
                .let { if (order == null) it else it.sortedWith(order) }
        }
    }
}
