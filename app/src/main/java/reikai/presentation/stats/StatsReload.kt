package reikai.presentation.stats

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.util.lang.launchIO

/**
 * What a Statistics screen shows, loaded again on each [reload]. [value] is null until the first load
 * lands, then keeps the last one until the next replaces it, so a reload never flashes the loading state.
 */
class StatsReload<T : Any>(private val scope: CoroutineScope, private val load: suspend () -> T) {

    val value: StateFlow<T?>
        field = MutableStateFlow<T?>(null)

    private var job: Job? = null

    /** Cancels a load still running, so an older load cannot land after a newer one. */
    fun reload() {
        job?.cancel()
        job = scope.launchIO { value.value = load() }
    }
}

/**
 * Reloads a Statistics screen each time it comes back on screen. ON_RESUME is replayed when the
 * composable re-enters (a tab switch back, a pushed screen popped) and fires again when the activity
 * resumes (back from the reader), which re-entry alone would miss.
 */
@Composable
fun StatsRefreshOnResume(refresh: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME, onEvent = refresh)
}
