package reikai.data.track

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.RefreshNovelTracks
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pulls fresh remote state for every tracker bound to a library entry, both content types. Without it
 * a track row only refreshes when its details screen is opened, so the library's tracker-score sort
 * and tracker filter read whatever happened to be cached.
 *
 * Manual only, and deliberately NOT folded into the library chapter update: that runs on a schedule
 * per source, while this is one rate-limited network call per bound tracker per entry, so attaching it
 * would multiply every update's remote traffic invisibly. Only entries carrying a track are visited.
 * Both interactors are merge-group aware, so a grouped entry refreshes every tracker in its group.
 */
class TrackerRefreshJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val getLibraryManga: GetLibraryManga = Injekt.get()
    private val novelRepository: NovelRepository = Injekt.get()
    private val getTracksPerManga: GetTracksPerManga = Injekt.get()
    private val getNovelTracks: GetNovelTracks = Injekt.get()
    private val refreshTracks: RefreshTracks = Injekt.get()
    private val refreshNovelTracks: RefreshNovelTracks = Injekt.get()
    private val trackerManager: TrackerManager = Injekt.get()
    private val mergeGroupRepository: MergeGroupRepository = Injekt.get()

    // WorkManager's own cancel intent for this run, so the notification action needs no receiver.
    private val cancelIntent: PendingIntent by lazy { context.workManager.createCancelPendingIntent(id) }

    private val notifier by lazy { TrackerRefreshNotifier(context, cancelIntent) }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.tracker_refresh_progress))
            setSmallIcon(android.R.drawable.stat_notify_sync)
            setOngoing(true)
            setOnlyAlertOnce(true)
            priority = NotificationCompat.PRIORITY_LOW
            addAction(R.drawable.ic_close_24dp, context.stringResource(MR.strings.action_cancel), cancelIntent)
        }.build()
        return ForegroundInfo(
            Notifications.ID_TRACKER_REFRESH_PROGRESS,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override suspend fun doWork(): Result {
        return try {
            // Foreground like every other long worker: without this a backgrounded refresh dies
            // with the cached process and WorkManager silently reruns it from zero later,
            // repeating every tracker call.
            setForegroundSafely()
            refreshAll()
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) {
                Result.success()
            } else {
                logcat(LogPriority.ERROR, e)
                Result.failure()
            }
        } finally {
            context.cancelNotification(Notifications.ID_TRACKER_REFRESH_PROGRESS)
        }
    }

    private suspend fun refreshAll() {
        // Only trackers you are signed into can answer, so a track row bound to a signed-out service is
        // not work to do. Filtering here rather than inside the interactors is what keeps the reported
        // count honest: it counts entries that actually had something to refresh.
        val loggedIn = trackerManager.loggedInTrackers().mapTo(mutableSetOf()) { it.id }
        if (loggedIn.isEmpty()) {
            notifier.showNothingTracked()
            return
        }

        // Intersect favorites with the entries carrying a live track: a track row survives removing an
        // entry from the library, so refreshing straight off the track table would hit entries nobody sees.
        val mangaTracks = getTracksPerManga.subscribe().first()
        val mangaIds = getLibraryManga.await()
            .map { it.id }
            .filter { id -> mangaTracks[id]?.any { it.trackerId in loggedIn } == true }
            .dedupeByGroup(mergeGroupRepository.getAllMemberships(ContentType.MANGA))

        val novelTracks = getNovelTracks.subscribeAll().first()
        val novelIds = novelRepository.getLibraryNovelAsFlow().first()
            .map { it.novel.id }
            .filter { id -> novelTracks[id]?.any { it.trackerId in loggedIn } == true }
            .dedupeByGroup(mergeGroupRepository.getAllMemberships(ContentType.NOVELS))

        val total = mangaIds.size + novelIds.size
        if (total == 0) {
            notifier.showNothingTracked()
            return
        }

        // Lower than the chapter updater's 5: every permit here is a remote tracker call, and the same
        // few services answer for the whole library rather than the load spreading across many sources.
        val semaphore = Semaphore(3)
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        // Which services failed, so the result names them instead of leaving a bare count. The per-track
        // errors are otherwise swallowed by the interactors, which is what made a failure undiagnosable.
        val failedTrackers = ConcurrentHashMap.newKeySet<String>()

        suspend fun refresh(id: Long, label: String, block: suspend (Long) -> List<Pair<Tracker?, Throwable>>) {
            semaphore.withPermit {
                val errors = block(id)
                if (errors.isNotEmpty()) {
                    failed.incrementAndGet()
                    errors.forEach { (tracker, error) ->
                        tracker?.name?.let(failedTrackers::add)
                        logcat(LogPriority.WARN) {
                            "Tracker refresh failed for $label $id on ${tracker?.name ?: "unknown"}: $error"
                        }
                    }
                }
                notifier.showProgress(done.incrementAndGet(), total)
            }
        }

        coroutineScope {
            mangaIds.forEach { id -> launch { refresh(id, "manga", refreshTracks::await) } }
            novelIds.forEach { id -> launch { refresh(id, "novel", refreshNovelTracks::await) } }
        }

        notifier.showResult(
            refreshed = total - failed.get(),
            failed = failed.get(),
            failedTrackers = failedTrackers.sorted(),
        )
    }

    /** One refresh per merge group: the interactors refresh the group's canonical rows, so two
     *  members that each carry track rows would refresh the same rows twice. */
    private fun List<Long>.dedupeByGroup(membership: Map<Long, Long>): List<Long> {
        val seenGroups = HashSet<Long>()
        return filter { id -> membership[id]?.let(seenGroups::add) ?: true }
    }

    companion object {
        private const val WORK_NAME = "TrackerRefresh"

        fun isRunning(context: Context): Boolean = context.workManager.isRunning(WORK_NAME)

        /** Starts a refresh, or returns false when one is already running. */
        fun startNow(context: Context): Boolean {
            if (isRunning(context)) return false
            val request = OneTimeWorkRequestBuilder<TrackerRefreshJob>()
                .addTag(WORK_NAME)
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            return true
        }
    }
}

/** Progress and result notifications for [TrackerRefreshJob], on the shared library channels. */
private class TrackerRefreshNotifier(
    private val context: Context,
    cancelIntent: PendingIntent,
) {

    private val progressBuilder = context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
        setContentTitle(context.stringResource(MR.strings.tracker_refresh_progress))
        setSmallIcon(android.R.drawable.stat_notify_sync)
        setOngoing(true)
        setOnlyAlertOnce(true)
        priority = NotificationCompat.PRIORITY_LOW
        addAction(R.drawable.ic_close_24dp, context.stringResource(MR.strings.action_cancel), cancelIntent)
    }

    fun showProgress(done: Int, total: Int) {
        context.notify(
            Notifications.ID_TRACKER_REFRESH_PROGRESS,
            progressBuilder.setProgress(total, done, false).build(),
        )
    }

    fun showNothingTracked() {
        context.notify(Notifications.ID_TRACKER_REFRESH_RESULT, Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.tracker_refresh_none))
            setSmallIcon(android.R.drawable.stat_notify_sync)
            setAutoCancel(true)
        }
    }

    fun showResult(refreshed: Int, failed: Int, failedTrackers: List<String>) {
        context.notify(
            Notifications.ID_TRACKER_REFRESH_RESULT,
            Notifications.CHANNEL_LIBRARY_PROGRESS,
        ) {
            setContentTitle(context.stringResource(MR.strings.tracker_refresh_done, refreshed))
            if (failed > 0) {
                // Naming the services turns "7 failed" into something the user can act on, usually
                // re-signing in to one of them.
                setContentText(
                    context.stringResource(MR.strings.tracker_refresh_failed, failed) +
                        failedTrackers.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = ": ").orEmpty(),
                )
            }
            setSmallIcon(android.R.drawable.stat_notify_sync)
            setAutoCancel(true)
        }
    }
}
