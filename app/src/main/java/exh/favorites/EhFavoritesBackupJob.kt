package exh.favorites

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import exh.eh.EHentaiUpdateNotifier
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.source.EXH_SOURCE_ID
import exh.source.ExhPreferences
import exh.source.isEhBasedManga
import exh.util.ThrottleManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.injectLazy

/**
 * RK: one-time backfill that pushes every E-Hentai gallery in the library to the account's
 * favorites (one-way add). Used by the "Back up all favorites now" settings action; steady-state
 * capture happens on favorite via MangaScreenModel. Never removes anything from the account.
 */
class EhFavoritesBackupJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    private val exhPreferences: ExhPreferences by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val mangaRepository: MangaRepository by injectLazy()

    private val notifier by lazy { EHentaiUpdateNotifier(context) }

    override suspend fun doWork(): Result {
        if (!exhPreferences.enableExhentai().get()) return Result.success()
        val source = sourceManager.get(EXH_SOURCE_ID) as? EHentai ?: return Result.success()

        return try {
            setForegroundSafely()

            // Read what's already on the account so we only push the missing ones.
            val remoteGids = source.fetchFavorites().first
                .map { EHentaiSearchMetadata.galleryId(it.manga.url) }
                .toSet()

            val toPush = mangaRepository.getFavorites()
                .filter { it.isEhBasedManga() }
                .filter { EHentaiSearchMetadata.galleryId(it.url) !in remoteGids }

            val slot = exhPreferences.exhFavoritesBackupSlot().get()
            val throttle = ThrottleManager()
            toPush.forEachIndexed { index, manga ->
                notifier.showProgressNotification(manga, index, toPush.size)
                throttle.throttle()
                runCatching {
                    source.addFavorite(
                        EHentaiSearchMetadata.galleryId(manga.url),
                        EHentaiSearchMetadata.galleryToken(manga.url),
                        slot,
                    )
                }.onFailure {
                    // runCatching also catches CancellationException; rethrow it so a cancelled job
                    // stops issuing account writes instead of logging cancellation as a failure.
                    if (it is CancellationException) throw it
                    logcat(LogPriority.ERROR, it) { "Failed to back up gallery ${manga.id}" }
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "E-Hentai favorites backup failed" }
            Result.success()
        } finally {
            notifier.cancelProgressNotification()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_EHENTAI_PROGRESS,
            notifier.progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG = "EhFavoritesBackup"

        fun startNow(context: Context) {
            context.workManager.enqueue(
                OneTimeWorkRequestBuilder<EhFavoritesBackupJob>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag(TAG)
                    .build(),
            )
        }
    }
}
