package reikai.data.work

import androidx.core.util.Consumer
import androidx.work.WorkerExceptionInfo
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * A background job that throws while it is built. WorkManager only logs that, and a periodic job then
 * fails the same way every period, so the reader is told through [show], once per job per app run.
 * Installed as WorkManager's worker initialization handler, which runs on its task executor.
 */
class WorkerStartFailures(private val show: (workerName: String) -> Unit) : Consumer<WorkerExceptionInfo> {

    private val shown = ConcurrentHashMap.newKeySet<String>()

    override fun accept(value: WorkerExceptionInfo) {
        report(value.workerClassName, value.throwable)
    }

    fun report(workerClassName: String, throwable: Throwable) {
        logcat(LogPriority.ERROR, throwable) { "Background job $workerClassName could not start" }
        if (shown.add(workerClassName)) show(workerClassName.substringAfterLast('.'))
    }
}
