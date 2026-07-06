package exh.md

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import exh.source.MANGADEX_IDS
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

/**
 * Two-way MangaDex sync worker: imports the account's follows into the library, or pushes library
 * favorites back as READING follows. One-shot, driven from the MangaDex settings screen. Re-typed
 * from Komikku's LibraryUpdateJob.syncFollows / pushFavorites.
 */
class MangaDexSyncJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val getManga: GetManga by injectLazy()
    private val networkToLocalManga: NetworkToLocalManga by injectLazy()
    private val updateMangaFromRemote: UpdateMangaFromRemote by injectLazy()
    private val updateManga: UpdateManga by injectLazy()
    private val getLibraryManga: GetLibraryManga by injectLazy()
    private val getTracks: GetTracks by injectLazy()
    private val insertTrack: InsertTrack by injectLazy()
    private val trackerManager: TrackerManager by injectLazy()
    private val reikaiSourcePreferences: ReikaiSourcePreferences by injectLazy()

    enum class Target { SYNC_FOLLOWS, PUSH_FAVORITES }

    private val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_MANGADEX) {
            setContentTitle(context.stringResource(MR.strings.app_name))
            setSmallIcon(android.R.drawable.stat_notify_sync)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
    }

    override suspend fun doWork(): Result {
        val target = inputData.getString(KEY_TARGET)?.let { Target.valueOf(it) } ?: return Result.failure()
        return try {
            setForegroundSafely()
            val count = when (target) {
                Target.SYNC_FOLLOWS -> syncFollows()
                Target.PUSH_FAVORITES -> pushFavorites()
            }
            val resultText = when (target) {
                Target.SYNC_FOLLOWS -> context.stringResource(MR.strings.pref_mangadex_sync_follows_result, count)
                Target.PUSH_FAVORITES -> context.stringResource(MR.strings.pref_mangadex_push_favorites_result, count)
            }
            showComplete(resultText)
            // Toast for immediate confirmation while the app is open; the notification covers the rest.
            withUIContext { context.toast(resultText) }
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "MangaDex sync job failed ($target)" }
            Result.failure()
        } finally {
            context.notificationManager.cancel(Notifications.ID_MANGADEX_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_MANGADEX_PROGRESS,
            progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun showProgress(title: String, current: Int, total: Int) {
        context.notificationManager.notify(
            Notifications.ID_MANGADEX_PROGRESS,
            progressNotificationBuilder
                .setContentText(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(title))
                .setProgress(total, current, false)
                .build(),
        )
    }

    // A persistent completion notification with the count, so a fast sync still gives feedback.
    private fun showComplete(text: String) {
        context.notificationManager.notify(
            Notifications.ID_MANGADEX_COMPLETE,
            context.notificationBuilder(Notifications.CHANNEL_MANGADEX) {
                setContentTitle(context.stringResource(MR.strings.app_name))
                setContentText(text)
                setSmallIcon(android.R.drawable.stat_notify_sync)
                setAutoCancel(true)
            }.build(),
        )
    }

    // Import the account's follows whose status is selected, adding each to the library as a favorite.
    // Returns the number of follows processed.
    private suspend fun syncFollows(): Int {
        val mangaDex = MdUtil.getEnabledMangaDex() ?: return 0
        val statuses = reikaiSourcePreferences.mangadexSyncToLibraryIndexes.get()
            .mapNotNull { it.toIntOrNull() }
            .toSet()
        val follows = mangaDex.fetchAllFollows().filter { (_, meta) -> meta.followStatus in statuses }

        follows.forEachIndexed { i, (sManga, _) ->
            currentCoroutineContext().ensureActive()
            showProgress(sManga.title, i, follows.size)

            var local = getManga.await(sManga.url, mangaDex.id)
                ?: networkToLocalManga(sManga.toDomainManga(mangaDex.id))
            // UpdateMangaFromRemote runs the enhanced metadata round-trip, so it persists the rich
            // flat metadata (rating, tags). Do NOT insert the follows-list metadata afterwards: it
            // only carries followStatus and would blank the rating until a manual refresh.
            local = updateMangaFromRemote(mangaDex, local, fetchDetails = true, fetchChapters = true)
                .getOrThrow().manga
            if (!local.favorite) {
                updateManga.awaitUpdateFavorite(local.id, true)
            }
        }
        return follows.size
    }

    // Push each library MangaDex favorite that is UNFOLLOWED on the account up as a READING follow.
    // Returns the number of favorites newly followed.
    private suspend fun pushFavorites(): Int {
        if (!trackerManager.mdList.isLoggedIn) return 0
        val favourites = getLibraryManga.await()
            .map { it.manga }
            .filter { it.source in MANGADEX_IDS }
            .distinctBy { it.id }

        var pushed = 0
        favourites.forEachIndexed { i, manga ->
            currentCoroutineContext().ensureActive()
            showProgress(manga.title, i, favourites.size)

            val tracks = getTracks.await(manga.id)
            var tracker = tracks.firstOrNull { it.trackerId == TrackerManager.MDLIST }
                ?: trackerManager.mdList.createInitialTracker(manga).toDomainTrack(idRequired = false)

            if (tracker?.status == FollowStatus.UNFOLLOWED.long) {
                tracker = tracker.copy(status = FollowStatus.READING.long)
                val updated = trackerManager.mdList.update(tracker.toDbTrack())
                insertTrack.await(updated.toDomainTrack(idRequired = false)!!)
                pushed++
            }
        }
        return pushed
    }

    companion object {
        private const val TAG = "MangaDexSync"
        private const val KEY_TARGET = "target"

        fun startNow(context: Context, target: Target) {
            val request = OneTimeWorkRequestBuilder<MangaDexSyncJob>()
                .addTag(TAG)
                .setInputData(workDataOf(KEY_TARGET to target.name))
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
