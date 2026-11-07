package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.data.library.updateBySource
import reikai.data.novel.NovelMetadataRefresh
import reikai.domain.download.SourceContacts
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

@OptIn(ExperimentalAtomicApi::class)
class MetadataUpdateWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    // RK: injected in init rather than at the top of doWork, since WorkManager may call getForegroundInfo first.
    init {
        graph.inject(this)
    }

    @Inject private lateinit var sourceManager: SourceManager

    @Inject private lateinit var getLibraryManga: GetLibraryManga

    @Inject private lateinit var updateMangaFromRemote: UpdateMangaFromRemote

    @Inject private lateinit var notifier: LibraryUpdateNotifier

    @Inject private lateinit var novelMetadataRefresh: NovelMetadataRefresh // RK

    @Inject private lateinit var sourceContacts: SourceContacts // RK

    private var mangaToUpdate: List<LibraryManga> = mutableListOf()

    override suspend fun doWork(): Result {
        // RK: graph.inject moved to init
        setForegroundSafely()

        addMangaToQueue()

        return withIOContext {
            try {
                updateMetadata()
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // Assume success although cancelled
                    Result.success()
                } else {
                    logcat(LogPriority.ERROR, e)
                    Result.failure()
                }
            } finally {
                notifier.cancelProgressNotification()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_LIBRARY_PROGRESS,
            notifier.progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Adds list of manga to be updated.
     */
    private suspend fun addMangaToQueue() {
        mangaToUpdate = getLibraryManga.await()
        notifier.showQueueSizeWarningNotificationIfNeeded(mangaToUpdate)
    }

    private suspend fun updateMetadata() {
        // RK: the kernel below replaces upstream's semaphore here
        val progressCount = AtomicInt(0)
        val currentlyUpdatingManga = CopyOnWriteArrayList<Manga>()

        // RK: the kernel the novel pass and both update jobs run on
        updateBySource(mangaToUpdate, sourceOf = { it.manga.source }, sourceContacts) { libraryManga ->
            val manga = libraryManga.manga

            withUpdateNotification(
                currentlyUpdatingManga,
                progressCount,
                manga,
            ) {
                val source = sourceManager.get(manga.source) ?: return@withUpdateNotification
                try {
                    updateMangaFromRemote(
                        source = source,
                        manga = manga,
                        fetchDetails = true,
                    ).getOrThrow()
                } catch (e: Throwable) {
                    // Ignore errors and continue
                    logcat(LogPriority.ERROR, e)
                }
            }
        }

        novelMetadataRefresh.run(notifier::showProgressTitles) // RK: the one Settings action covers both content types

        notifier.cancelProgressNotification()
    }

    private suspend fun withUpdateNotification(
        updatingManga: CopyOnWriteArrayList<Manga>,
        completed: AtomicInt,
        manga: Manga,
        block: suspend () -> Unit,
    ) = coroutineScope {
        ensureActive()

        updatingManga.add(manga)
        notifier.showProgressNotification(
            updatingManga,
            completed.load(),
            mangaToUpdate.size,
        )

        block()

        ensureActive()

        updatingManga.remove(manga)
        completed.fetchAndIncrement()
        notifier.showProgressNotification(
            updatingManga,
            completed.load(),
            mangaToUpdate.size,
        )
    }

    companion object {
        private const val TAG = "MetadataUpdate"
        private const val WORK_NAME_MANUAL = "MetadataUpdate"

        private const val MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 60

        // RK: upstream's shared progress notice cancels only the library update, which left a refresh running
        fun stop(workManager: WorkManager) {
            workManager.cancelUniqueWork(WORK_NAME_MANUAL)
        }

        suspend fun startNow(workManager: WorkManager): Boolean {
            if (workManager.isRunning(TAG)) {
                // Already running either as a scheduled or manual job
                return false
            }
            val request = OneTimeWorkRequestBuilder<MetadataUpdateWorker>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .build()
            workManager.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)

            return true
        }
    }
}
