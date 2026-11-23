package reikai.presentation.stats

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * What a Statistics screen shows, loaded again on each [reload]. [value] is null until the first load
 * lands, then keeps the last one until the next replaces it, so a reload never flashes the loading state.
 */
class StatsReload<T : Any>(
    private val scope: CoroutineScope,
    private val load: suspend () -> T,
    private val loadOn: CoroutineDispatcher = Dispatchers.IO,
) {

    val value: StateFlow<T?>
        field = MutableStateFlow<T?>(null)

    private var job: Job? = null
    private val latest = AtomicLong()

    /**
     * Only the latest load's result lands. Cancelling the older one is not enough: a load is blocking
     * database work, which runs to the end once started, and would otherwise overwrite a newer result.
     */
    fun reload() {
        val mine = latest.incrementAndGet()
        job?.cancel()
        job = scope.launch(loadOn) {
            val loaded = load()
            if (latest.get() == mine) value.value = loaded
        }
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
