package reikai.data.task

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** A background job the app-state banner can show. The manga and novel updates are one library update there. */
enum class AppTask { MANGA_UPDATE, NOVEL_UPDATE, RESTORE }

/** How far a job has got; a [total] of 0 means it has not counted its work yet. */
data class TaskCount(val done: Int, val total: Int)

/** What the banner shows, with [progress] in 0..1, or null while no total is known. */
sealed interface BannerTask {
    val progress: Float?

    data class Updating(override val progress: Float?) : BannerTask

    data class Restoring(override val progress: Float?) : BannerTask
}

/** The jobs running now, each started and stopped by [during] and counted by [report]. */
@Inject
@SingleIn(AppScope::class)
class AppTaskProgress {
    val running: StateFlow<Map<AppTask, TaskCount>>
        field = MutableStateFlow<Map<AppTask, TaskCount>>(emptyMap())

    suspend fun <T> during(task: AppTask, block: suspend () -> T): T {
        running.update { it + (task to TaskCount(0, 0)) }
        try {
            return block()
        } finally {
            running.update { it - task }
        }
    }

    /** Ignored outside [during], so a notifier's last call after the job ends cannot bring the banner back. */
    fun report(task: AppTask, done: Int, total: Int) {
        running.update { if (task in it) it + (task to TaskCount(done, total)) else it }
    }
}

/**
 * A library update shows before a restore, its manga and novel counts summed into one percent. Each kind
 * shows only while its setting is on.
 */
fun bannerTask(running: Map<AppTask, TaskCount>, showUpdating: Boolean, showRestoring: Boolean): BannerTask? {
    val updates = running.filterKeys { it != AppTask.RESTORE }.values
    if (showUpdating && updates.isNotEmpty()) return BannerTask.Updating(fraction(updates))
    val restore = running[AppTask.RESTORE]
    if (showRestoring && restore != null) return BannerTask.Restoring(fraction(listOf(restore)))
    return null
}

private fun fraction(counts: Collection<TaskCount>): Float? {
    val total = counts.sumOf { it.total }
    if (total <= 0) return null
    return (counts.sumOf { it.done }.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * The banner's task, held back [SHOW_DELAY_MS] after a job starts so a quick one never flashes it (from
 * Komikku). Only appearing waits: the percent and the hiding follow at once.
 */
@OptIn(FlowPreview::class)
fun AppTaskProgress.banner(showUpdating: Flow<Boolean>, showRestoring: Flow<Boolean>): Flow<BannerTask?> = combine(
    running.map { it.isNotEmpty() }.distinctUntilChanged().debounce(SHOW_DELAY_MS),
    running,
    showUpdating,
    showRestoring,
) { settled, running, showUpdating, showRestoring ->
    if (settled) bannerTask(running, showUpdating, showRestoring) else null
}
    .distinctUntilChanged()

internal const val SHOW_DELAY_MS = 1000L
