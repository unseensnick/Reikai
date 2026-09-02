package reikai.presentation.browse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState

/** How many rows of one group run at once, so a slow group never starves another of its slots. */
const val ENTRY_ROW_CONCURRENCY = 5

/**
 * Fills every still-loading row of [rows] by running [load] against it, a few per [group] at a time,
 * and writing each result the moment it lands rather than when the slowest finishes. One row failing
 * costs that row alone.
 *
 * Shared because this shape is got wrong the same three ways every time: one limiter across all
 * groups, a read outside the write, and a superseded pass writing over its replacement.
 */
suspend fun fillEntryRows(
    rows: List<BrowseSearchRow>,
    group: (BrowseSearchRow) -> Any,
    /** Re-applied as each row lands, so a row that answers can move. Null keeps the given order. */
    order: Comparator<BrowseSearchRow>? = null,
    concurrency: Int = ENTRY_ROW_CONCURRENCY,
    /** Applies a change to the current rows. The caller supplies this so the read and the write stay
     *  inside one state update; reading outside would let two results race onto one snapshot. */
    updateRows: ((List<BrowseSearchRow>) -> List<BrowseSearchRow>) -> Unit,
    load: suspend (BrowseSearchRow) -> List<Any>,
): Unit = coroutineScope {
    val semaphores = rows.map(group).distinct().associateWith { Semaphore(concurrency) }
    rows.filter { it.state is EntrySearchState.Loading }
        .map { row ->
            async {
                val result = semaphores.getValue(group(row)).withPermit {
                    try {
                        EntrySearchState.Success(load(row))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        EntrySearchState.Error(e.message)
                    }
                }
                // Skipped once cancelled, so a superseded pass cannot write onto the one that
                // replaced it.
                if (!isActive) return@async
                updateRows { current ->
                    current
                        .map { if (it.key == row.key) it.copy(state = result) else it }
                        .let { if (order == null) it else it.sortedWith(order) }
                }
            }
        }
        .awaitAll()
}
