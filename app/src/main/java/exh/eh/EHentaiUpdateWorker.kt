package exh.eh

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.copyFrom
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.source.ExhPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days

class EHentaiUpdateWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    private val exhPreferences: ExhPreferences by injectLazy()
    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val updateHelper: EHentaiUpdateHelper by injectLazy()
    private val updateManga: UpdateManga by injectLazy()
    private val syncChaptersWithSource: SyncChaptersWithSource by injectLazy()
    private val getChaptersByMangaId: GetChaptersByMangaId by injectLazy()
    private val getFlatMetadataById: GetFlatMetadataById by injectLazy()
    private val insertFlatMetadata: InsertFlatMetadata by injectLazy()
    private val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata by injectLazy()

    private val updateNotifier by lazy { EHentaiUpdateNotifier(context) }
    private val libraryUpdateNotifier by lazy { LibraryUpdateNotifier(context) }

    override suspend fun doWork(): Result {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P &&
                requiresWifiConnection(exhPreferences) &&
                !context.isConnectedToWifi()
            ) {
                // Retry again later in next periodic run due to missing Wi-Fi connection.
                Result.success()
            } else {
                setForegroundSafely()
                startUpdating()
                Result.success()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "EHentai update job failed, retrying next run" }
            Result.success() // retry again later in next periodic run
        } finally {
            updateNotifier.cancelProgressNotification()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_EHENTAI_PROGRESS,
            updateNotifier.progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private suspend fun startUpdating() {
        val startTime = System.currentTimeMillis()

        val metadataManga = getExhFavoriteMangaWithMetadata.await()

        val allMeta = metadataManga.mapNotNull { manga ->
            val meta = getFlatMetadataById.await(manga.id) ?: return@mapNotNull null
            val raisedMeta = meta.raise<EHentaiSearchMetadata>()

            // Don't update aged (dead) galleries, nor galleries checked too recently.
            if (raisedMeta.aged || startTime - raisedMeta.lastUpdateCheck < MIN_BACKGROUND_UPDATE_FREQ) {
                return@mapNotNull null
            }

            UpdateEntry(manga, raisedMeta)
        }.sortedBy { it.meta.lastUpdateCheck }

        val mangaMetaToUpdateThisIter = allMeta.take(UPDATES_PER_ITERATION)

        var failuresThisIteration = 0
        var updatedThisIteration = 0
        val updatedManga = mutableListOf<Pair<Manga, Array<Chapter>>>()
        val failedUpdates = mutableListOf<Pair<Manga, String?>>()
        val modifiedThisIteration = mutableSetOf<Long>()

        try {
            for ((index, entry) in mangaMetaToUpdateThisIter.withIndex()) {
                val manga = entry.manga
                if (failuresThisIteration > MAX_UPDATE_FAILURES) {
                    logcat(LogPriority.WARN) { "Too many update failures, aborting EHentai update job" }
                    break
                }

                if (manga.id in modifiedThisIteration) {
                    // We already processed this manga this iteration!
                    updatedThisIteration++
                    continue
                }

                val (new, chapters) = try {
                    updateNotifier.showProgressNotification(
                        manga,
                        updatedThisIteration + failuresThisIteration,
                        mangaMetaToUpdateThisIter.size,
                    )
                    updateEntryAndGetChapters(manga)
                } catch (e: GalleryNotUpdatedException) {
                    if (e.network) {
                        failuresThisIteration++
                        failedUpdates += manga to (e.cause?.message ?: e.message)
                        logcat(LogPriority.ERROR, e) { "Network error while updating EHentai gallery ${manga.id}" }
                    }
                    continue
                }

                if (chapters.isEmpty()) {
                    logcat(LogPriority.ERROR) { "No chapters found for EHentai gallery ${manga.id}" }
                    continue
                }

                // Find accepted root and discard others.
                val (acceptedRoot, discardedRoots, exhNew) =
                    updateHelper.findAcceptedRootAndDiscardOthers(manga.source, chapters) ?: continue

                if (new.isNotEmpty() && manga.id == acceptedRoot.manga.id) {
                    libraryPreferences.newUpdatesCount.getAndSet { it + new.size }
                    updatedManga += acceptedRoot.manga to new.toTypedArray()
                } else if (exhNew.isNotEmpty() && updatedManga.none { it.first.id == acceptedRoot.manga.id }) {
                    libraryPreferences.newUpdatesCount.getAndSet { it + exhNew.size }
                    updatedManga += acceptedRoot.manga to exhNew.toTypedArray()
                }

                modifiedThisIteration += acceptedRoot.manga.id
                modifiedThisIteration += discardedRoots.map { it.manga.id }
                updatedThisIteration++
            }
        } finally {
            exhPreferences.exhAutoUpdateStats().set(
                Json.encodeToString(
                    EHentaiUpdaterStats(
                        startTime,
                        allMeta.size,
                        updatedThisIteration,
                    ),
                ),
            )

            updateNotifier.cancelProgressNotification()
            if (updatedManga.isNotEmpty()) {
                libraryUpdateNotifier.showUpdateNotifications(updatedManga)
            }
            if (failedUpdates.isNotEmpty()) {
                val errorFile = writeErrorFile(failedUpdates)
                updateNotifier.showUpdateErrorNotification(failedUpdates.size, errorFile.getUriCompat(context))
            }
        }
    }

    /**
     * Dumps the galleries that failed to update into a cache file, one line per gallery grouped by
     * error, so the error notification can open a readable log (mirrors LibraryUpdateJob's format).
     */
    private fun writeErrorFile(errors: List<Pair<Manga, String?>>): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("reikai_ehentai_update_errors.txt")
                file.bufferedWriter().use { out ->
                    errors.groupBy({ it.second }, { it.first }).forEach { (error, mangas) ->
                        out.write("! $error\n")
                        mangas.forEach { out.write("  - ${it.title}\n") }
                        out.write("\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {}
        return File("")
    }

    private suspend fun updateEntryAndGetChapters(manga: Manga): Pair<List<Chapter>, List<Chapter>> {
        val source = sourceManager.get(manga.source) as? EHentai
            ?: throw GalleryNotUpdatedException(
                false,
                IllegalStateException("Missing EH-based source (${manga.source})!"),
            )

        try {
            // Komikku splits getMangaDetails + getChapterList; Reikai's source-api combines them.
            val update = source.getMangaUpdate(manga.toSManga(), emptyList(), fetchDetails = true, fetchChapters = true)
            updateManga.awaitAll(listOf(manga.copyFrom(update.manga).toMangaUpdate()))

            val new = syncChaptersWithSource.await(update.chapters, manga, source)
            return new to getChaptersByMangaId.await(manga.id)
        } catch (t: Throwable) {
            if (t is EHentai.GalleryNotFoundException) {
                val meta = getFlatMetadataById.await(manga.id)?.raise<EHentaiSearchMetadata>()
                if (meta != null) {
                    // Age dead galleries so they stop being rechecked.
                    meta.aged = true
                    insertFlatMetadata.await(meta)
                }
                throw GalleryNotUpdatedException(false, t)
            }
            throw GalleryNotUpdatedException(true, t)
        }
    }

    private fun requiresWifiConnection(exhPreferences: ExhPreferences): Boolean {
        val restrictions = exhPreferences.exhAutoUpdateRequirements().get()
        return DEVICE_ONLY_ON_WIFI in restrictions
    }

    companion object {
        private const val MAX_UPDATE_FAILURES = 5
        private const val UPDATES_PER_ITERATION = 50

        private val MIN_BACKGROUND_UPDATE_FREQ = 1.days.inWholeMilliseconds

        private const val TAG = "EHBackgroundUpdater"

        fun launchBackgroundTest(context: Context) {
            context.workManager.enqueue(
                OneTimeWorkRequestBuilder<EHentaiUpdateWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag(TAG)
                    .build(),
            )
        }

        fun setupTask(context: Context, prefInterval: Int? = null, prefRestrictions: Set<String>? = null) {
            val exhPreferences = Injekt.get<ExhPreferences>()
            val interval = prefInterval ?: exhPreferences.exhAutoUpdateFrequency().get()
            if (interval > 0) {
                val restrictions = prefRestrictions ?: exhPreferences.exhAutoUpdateRequirements().get()
                val networkType = if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                }
                val networkRequest = NetworkRequest.Builder().apply {
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    if (DEVICE_ONLY_ON_WIFI in restrictions) {
                        addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    }
                    if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }
                }
                    .build()

                val constraints = Constraints.Builder()
                    // 'networkRequest' only applies to Android 9+, otherwise 'networkType' is used.
                    .setRequiredNetworkRequest(networkRequest, networkType)
                    .setRequiresCharging(DEVICE_CHARGING in restrictions)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val request = PeriodicWorkRequestBuilder<EHentaiUpdateWorker>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG)
                    .setConstraints(constraints)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
            } else {
                cancelBackground(context)
            }
        }

        fun cancelBackground(context: Context) {
            context.workManager.cancelAllWorkByTag(TAG)
        }
    }
}

private data class UpdateEntry(val manga: Manga, val meta: EHentaiSearchMetadata)
