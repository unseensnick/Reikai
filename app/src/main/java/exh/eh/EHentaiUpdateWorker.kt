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
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.source.ExhPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.data.updateerror.UpdateErrorEntry
import reikai.data.updateerror.UpdateErrorLog
import reikai.data.updateerror.UpdateErrorSection
import reikai.data.updateerror.updateFailureMessage
import reikai.domain.merge.ReconcileMergedChapters
import tachiyomi.core.common.i18n.stringResource
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
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days

class EHentaiUpdateWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    init {
        graph.inject(this)
    }

    @Inject private lateinit var exhPreferences: ExhPreferences

    @Inject private lateinit var libraryPreferences: LibraryPreferences

    @Inject private lateinit var sourceManager: SourceManager

    @Inject private lateinit var updateHelper: EHentaiUpdateHelper

    @Inject private lateinit var updateMangaFromRemote: UpdateMangaFromRemote

    @Inject private lateinit var getChaptersByMangaId: GetChaptersByMangaId

    @Inject private lateinit var getFlatMetadataById: GetFlatMetadataById

    @Inject private lateinit var insertFlatMetadata: InsertFlatMetadata

    @Inject private lateinit var getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata

    @Inject private lateinit var updateNotifier: EHentaiUpdateNotifier

    @Inject private lateinit var libraryUpdateNotifier: LibraryUpdateNotifier

    @Inject private lateinit var reconcileMergedChapters: ReconcileMergedChapters

    private val updateErrorLog = UpdateErrorLog(context)

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
        val failedUpdates = mutableListOf<UpdateErrorEntry>()
        val modifiedThisIteration = mutableSetOf<Long>()

        try {
            // A gallery grouped with other sources has a stored stitch, which new or moved chapters
            // leave stale until something rebuilds it, even when a later gallery fails the pass.
            reconcileMergedChapters.afterPass {
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
                            failedUpdates += UpdateErrorEntry(
                                title = manga.title,
                                sourceName = sourceManager.getOrStub(manga.source).toString(),
                                message = with(context) { (e.cause ?: e).updateFailureMessage() }
                                    ?: context.stringResource(MR.strings.unknown),
                            )
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
            // Rewritten on every run, so a gallery that has since updated leaves the shared dump.
            val errorFile = updateErrorLog.write(UpdateErrorSection.GALLERIES, failedUpdates)
            if (failedUpdates.isNotEmpty()) {
                updateNotifier.showUpdateErrorNotification(failedUpdates.size, errorFile.getUriCompat(context))
            }
        }
    }

    private suspend fun updateEntryAndGetChapters(manga: Manga): Pair<List<Chapter>, List<Chapter>> {
        val source = sourceManager.get(manga.source) as? EHentai
            ?: throw GalleryNotUpdatedException(
                false,
                IllegalStateException("Missing EH-based source (${manga.source})!"),
            )

        try {
            val result = updateMangaFromRemote(
                source = source,
                manga = manga,
                fetchDetails = true,
                fetchChapters = true,
            ).getOrThrow()
            return result.newChapters to getChaptersByMangaId.await(manga.id)
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
            val exhPreferences = context.appGraph.exhPreferences
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
