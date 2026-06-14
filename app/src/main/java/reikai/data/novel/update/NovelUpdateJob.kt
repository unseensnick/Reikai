package reikai.data.novel.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import reikai.data.novel.NovelStatusCode
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.data.novel.toNovel
import reikai.data.novel.walkNovelPages
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.mergeRefreshedNovel
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * RK: periodic background check for new chapters in favorited light novels (P5 S7). The novel analog
 * of Mihon's [eu.kanade.tachiyomi.data.library.LibraryUpdateJob]: a configurable WorkManager schedule
 * (interval + device restrictions live in [NovelPreferences]) that re-parses each favorite, syncs its
 * chapter list, optionally auto-downloads the new chapters, and posts progress + result notifications.
 *
 * Per-novel logic mirrors [reikai.presentation.novel.details.NovelDetailsScreenModel]'s refresh
 * (parseNovel + sync page 1 + [walkNovelPages] for paged sources); new chapters are captured by a
 * before/after chapter-id diff so the shared sync/walk helpers stay untouched.
 */
class NovelUpdateJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val novelRepo: NovelRepository = Injekt.get()
    private val chapterRepo: NovelChapterRepository = Injekt.get()
    private val database: Database = Injekt.get()
    private val downloadManager: NovelDownloadManager = Injekt.get()
    private val sourceManager: NovelSourceManager = Injekt.get()
    private val installer: LnPluginInstaller = Injekt.get()
    private val getNovelCategories: GetNovelCategories = Injekt.get()
    private val preferences: NovelPreferences = Injekt.get()
    private val notifier = NovelUpdateNotifier(context)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progress("", 0, 0)
        val id = Notifications.ID_NOVEL_LIBRARY_PROGRESS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    override suspend fun doWork(): Result {
        setForegroundSafely()
        return try {
            withIOContext { updateNovels() }
            Result.success()
        } catch (_: CancellationException) {
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.retry()
        } finally {
            notifier.dismissProgress()
        }
    }

    private suspend fun updateNovels() {
        // One load brings every installed plugin into the host; per-novel resolution is then cheap.
        runCatching { installer.ensureLoaded() }

        var favorites = novelRepo.getFavorites()
        if (preferences.updateOnlyOngoing().get()) {
            favorites = favorites.filter { it.status != NovelStatusCode.COMPLETED.toLong() }
        }
        if (favorites.isEmpty()) return

        val downloadNew = preferences.downloadNewChapters().get()
        var updatedCount = 0
        favorites.forEachIndexed { index, novel ->
            currentCoroutineContext().ensureActive()
            notifier.showProgress(novel.title, index, favorites.size)
            val source = sourceManager.get(novel.source) ?: return@forEachIndexed
            try {
                val newChapters = checkNovel(novel, source)
                if (newChapters.isNotEmpty()) {
                    updatedCount++
                    if (downloadNew) {
                        val toDownload = filterChaptersForDownload(novel, newChapters)
                        if (toDownload.isNotEmpty()) downloadManager.downloadChapters(toDownload)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Novel update failed: ${novel.title}" }
            }
        }
        notifier.showResult(updatedCount)
    }

    /** Re-parse the novel, persist metadata edit-lock-safely, sync page 1, and walk any newly-opened
     *  pages. Returns the chapters that did not exist before (the before/after id diff). */
    private suspend fun checkNovel(novel: Novel, source: NovelSource): List<NovelChapter> {
        val before = chapterRepo.getByNovelId(novel.id).map { it.id }.toSet()

        val sourceNovel = source.parseNovel(novel.url)
        val parsed = sourceNovel.toNovel(sourceId = source.id, favorite = novel.favorite)
        val merged = mergeRefreshedNovel(novel, parsed)
        if (merged != novel) novelRepo.update(merged)

        val firstChapters = sourceNovel.chapters.orEmpty()
        if (firstChapters.isNotEmpty()) {
            val pageTag = if (sourceNovel.totalPages > 1) "1" else null
            syncChaptersWithNovelSource(firstChapters, merged, chapterRepo, novelRepo, database, page = pageTag)
        }
        if (merged.totalPages > 1L) {
            walkNovelPages(merged, source, maxOf(2L, novel.totalPages), merged.totalPages, chapterRepo, novelRepo, database)
        }

        val after = chapterRepo.getByNovelId(novel.id)
        return after.filter { it.id !in before }
    }

    /** Mirror of the manga FilterChaptersForDownload: gate by the novel's categories, then (when
     *  "skip duplicate read" is on) drop new chapters whose number matches an already-read one. */
    private suspend fun filterChaptersForDownload(novel: Novel, newChapters: List<NovelChapter>): List<NovelChapter> {
        if (!shouldDownloadFor(novel)) return emptyList()
        if (!preferences.downloadNewUnreadChaptersOnly().get()) return newChapters
        val readNumbers = chapterRepo.getByNovelId(novel.id)
            .asSequence()
            .filter { it.read && it.chapterNumber >= 0.0 }
            .map { it.chapterNumber }
            .toSet()
        return newChapters.filterNot { it.chapterNumber in readNumbers }
    }

    private suspend fun shouldDownloadFor(novel: Novel): Boolean {
        val categories = getNovelCategories.awaitByNovelId(novel.id).map { it.id }.ifEmpty { listOf(0L) }
        val included = preferences.downloadNewChapterCategories().get().map { it.toLong() }
        val excluded = preferences.downloadNewChapterCategoriesExclude().get().map { it.toLong() }
        return when {
            included.isEmpty() && excluded.isEmpty() -> true
            categories.any { it in excluded } -> false
            included.isEmpty() -> true
            else -> categories.any { it in included }
        }
    }

    companion object {
        private const val TAG = "NovelLibraryUpdate"
        private const val WORK_NAME_AUTO = "NovelLibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "NovelLibraryUpdate-manual"

        /** (Re)schedule or cancel the periodic check from the stored interval (0 = off). Idempotent. */
        fun setupTask(context: Context, prefInterval: Int? = null) {
            val preferences = Injekt.get<NovelPreferences>()
            val interval = prefInterval ?: preferences.libraryUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.libraryUpdateDeviceRestrictions().get()
                val networkType = if (LibraryPreferences.DEVICE_NETWORK_NOT_METERED in restrictions) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                }
                val networkRequest = NetworkRequest.Builder().apply {
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    if (LibraryPreferences.DEVICE_ONLY_ON_WIFI in restrictions) {
                        addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    }
                    if (LibraryPreferences.DEVICE_NETWORK_NOT_METERED in restrictions) {
                        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }
                }
                    .build()
                val constraints = Constraints.Builder()
                    // 'networkRequest' only applies to Android 9+, otherwise 'networkType' is used
                    .setRequiredNetworkRequest(networkRequest, networkType)
                    .setRequiresCharging(LibraryPreferences.DEVICE_CHARGING in restrictions)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val request = PeriodicWorkRequestBuilder<NovelUpdateJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    1,
                    TimeUnit.HOURS,
                )
                    .addTag(TAG)
                    .addTag(WORK_NAME_AUTO)
                    .setConstraints(constraints)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(
                    WORK_NAME_AUTO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                context.workManager.cancelUniqueWork(WORK_NAME_AUTO)
            }
        }

        /** Run a check immediately (for manual triggers / testing); reuses a running drain via KEEP. */
        fun startNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<NovelUpdateJob>().addTag(TAG).build()
            context.workManager.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)
        }

        /** Cancel the currently-running check by id (so the periodic schedule survives), re-enqueuing
         *  the auto schedule if that was what got cancelled. Mirrors the manga LibraryUpdateJob. */
        fun stop(context: Context) {
            val wm = context.workManager
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            wm.getWorkInfos(workQuery).get()
                .forEach {
                    wm.cancelWorkById(it.id)
                    if (it.tags.contains(WORK_NAME_AUTO)) {
                        setupTask(context)
                    }
                }
        }
    }
}
