package reikai.presentation.details

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

/**
 * [load] once per input, its latest value re-emitted on every [ticks] emission. A tick means the loaded
 * rows need rendering again (a download landed, the queue moved), not reading again: loading a merged
 * series subscribes every member's chapters. [ticks] must emit once before anything renders.
 */
internal fun <I, L> Flow<I>.loadThenRenderOn(ticks: Flow<*>, load: suspend (I) -> Flow<L>): Flow<L> =
    combine(flatMapLatest(load), ticks) { loaded, _ -> loaded }
