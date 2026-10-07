package reikai.domain.track

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import eu.kanade.domain.track.store.DelayedTrackingStore.DelayedTrackingItem
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * One run of a delayed-tracking job: a queued id whose track is gone leaves the queue, the rest are
 * pushed with their queued chapter, and the work retries while anything is left. A push that lands
 * takes its own item off the queue, so [push] never removes. Both the manga and the novel job run
 * this over their own queue file.
 */
suspend fun <T : Any> drainDelayedTracking(
    runAttemptCount: Int,
    items: () -> List<DelayedTrackingItem>,
    remove: (trackId: Long) -> Unit,
    trackOf: suspend (trackId: Long) -> T?,
    push: suspend (track: T, lastChapterRead: Double) -> Unit,
): ListenableWorker.Result {
    if (runAttemptCount > 3) {
        return ListenableWorker.Result.failure()
    }

    withIOContext {
        items().forEach { item ->
            val track = trackOf(item.trackId)
            if (track == null) {
                remove(item.trackId)
                return@forEach
            }
            val lastChapterRead = item.lastChapterRead.toDouble()
            logcat(LogPriority.DEBUG) {
                "Updating delayed track item: ${item.trackId}, last chapter read: $lastChapterRead"
            }
            push(track, lastChapterRead)
        }
    }

    return if (items().isEmpty()) ListenableWorker.Result.success() else ListenableWorker.Result.retry()
}

/** The retry both delayed-tracking workers schedule: once online, backing off from five minutes. */
inline fun <reified W : ListenableWorker> delayedTrackingRequest(tag: String): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<W>()
        .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
        .addTag(tag)
        .build()

/** A new failure replaces the pending retry, so each queue has at most one, named by its [tag]. */
inline fun <reified W : ListenableWorker> enqueueDelayedTracking(context: Context, tag: String) {
    context.workManager.enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, delayedTrackingRequest<W>(tag))
}
