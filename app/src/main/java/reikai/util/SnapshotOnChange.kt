package reikai.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * [read] re-run whenever any of these flows emits, passed on only when it differs from the last one.
 * Compared as a snapshot rather than by counting emissions: a preference's `changes()` fires once on
 * subscribe, so the first value is the current one, and a fixed drop count breaks the day an input is added.
 */
fun <T> List<Flow<*>>.snapshotOnChange(read: () -> T): Flow<T> = merge().map { read() }.distinctUntilChanged()
