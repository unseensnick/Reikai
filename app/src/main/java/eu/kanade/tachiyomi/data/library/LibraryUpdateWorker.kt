package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.nHentaiDelegatedSourceIds
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import exh.source.LIBRARY_UPDATE_EXCLUDED_SOURCES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import mihon.core.migration.Migrator
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.data.library.LibraryUpdateRun
import reikai.data.library.UpdateRunLedger
import reikai.data.library.libraryUpdateManualRequest
import reikai.data.library.libraryUpdatePeriodicRequest
import reikai.data.library.shouldDeferLibraryUpdate
import reikai.data.library.stopLibraryUpdate
import reikai.data.updateerror.UpdateErrorEntry
import reikai.data.updateerror.UpdateErrorLog
import reikai.data.updateerror.UpdateErrorSection
import reikai.data.updateerror.updateFailureMessage
import reikai.domain.category.isUpdateScope
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.smartUpdateFacts
import reikai.domain.library.smartUpdateSkip
import reikai.domain.library.updateerror.DeleteLibraryUpdateErrors
import reikai.domain.library.updateerror.UpsertLibraryUpdateError
import reikai.util.workRunningFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock

@OptIn(ExperimentalAtomicApi::class)
class LibraryUpdateWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    // RK: injected in init rather than at the top of doWork, see metro-di-migration.md "Workers inject".
    init {
        graph.inject(this)
    }

    @Inject private lateinit var sourceManager: SourceManager

    @Inject private lateinit var libraryPreferences: LibraryPreferences

    @Inject private lateinit var downloadManager: DownloadManager

    @Inject private lateinit var getLibraryManga: GetLibraryManga

    @Inject private lateinit var getManga: GetManga

    @Inject private lateinit var fetchInterval: FetchInterval

    @Inject private lateinit var filterChaptersForDownload: FilterChaptersForDownload

    @Inject private lateinit var updateMangaFromRemote: UpdateMangaFromRemote

    // RK: persistence of per-manga update failures (the Update errors screen), plus the dump file
    //     both content types share when that persistence is off
    @Inject private lateinit var reikaiLibraryPreferences: ReikaiLibraryPreferences

    @Inject private lateinit var upsertLibraryUpdateError: UpsertLibraryUpdateError

    @Inject private lateinit var deleteLibraryUpdateErrors: DeleteLibraryUpdateErrors
    private val updateErrorLog = UpdateErrorLog(context)

    // RK: reconciles the merged stitch after the run and collapses its arrivals, shared with novels
    @Inject private lateinit var libraryUpdateRun: LibraryUpdateRun

    @Inject private lateinit var notifier: LibraryUpdateNotifier

    private var mangaToUpdate: List<LibraryManga> = mutableListOf()

    override suspend fun doWork(): Result {
        Migrator.await() // RK: a WorkManager start has no MainActivity to wait for the migrations first
        // RK: graph.inject moved to init
        // RK: the deferral rule is shared with the novel updater, in LibraryUpdateSchedule.kt
        val restrictions = libraryPreferences.autoUpdateDeviceRestrictions.get()
        if (shouldDeferLibraryUpdate(restrictions, WORK_NAME_AUTO, WORK_NAME_MANUAL)) {
            return Result.retry()
        }

        setForegroundSafely()

        libraryPreferences.lastUpdatedTimestamp.set(Clock.System.now().toEpochMilliseconds())

        val categoryId = inputData.getLong(KEY_CATEGORY, -1L)
        addMangaToQueue(categoryId)

        return withIOContext {
            try {
                updateChapterList()
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
     *
     * @param categoryId the ID of the category to update, or -1 if no category specified.
     */
    private suspend fun addMangaToQueue(categoryId: Long) {
        val libraryManga = getLibraryManga.await()

        // RK --> the scope rule is a kernel the novel update job calls too
        val includedCategories = libraryPreferences.updateCategories.get().mapTo(mutableSetOf()) { it.toLong() }
        val excludedCategories = libraryPreferences.updateCategoriesExclude.get().mapTo(mutableSetOf()) { it.toLong() }
        val listToUpdate = libraryManga.filter {
            isUpdateScope(it.categories, categoryId, includedCategories, excludedCategories)
        }
        // RK <--

        val restrictions = libraryPreferences.autoUpdateMangaRestrictions.get()
        val skippedUpdates = mutableListOf<Pair<Manga, String?>>()
        val timeZone = TimeZone.currentSystemDefault()
        val (_, fetchWindowUpperBound) = fetchInterval.getWindow(
            Clock.System.now().toLocalDateTime(timeZone).date,
            timeZone,
        )

        // RK: the delegated nHentai ids are set when the extension scan lands, so a fresh process waits for it.
        sourceManager.getAll()
        mangaToUpdate = listToUpdate
            // RK -->
            // Adult galleries (E-Hentai / ExHentai / Pururin, plus delegated nHentai) are skipped
            // here: they never gain chapters the usual way and E-Hentai has its own version checker
            // (EHentaiUpdateWorker), so re-fetching whole galleries every update only burns requests
            // and risks rate-limits. nHentai's id varies by extension version, so it is resolved at
            // runtime (nHentaiDelegatedSourceIds) rather than baked into the static list.
            .filterNot {
                it.manga.source in LIBRARY_UPDATE_EXCLUDED_SOURCES ||
                    it.manga.source in nHentaiDelegatedSourceIds
            }
            // RK <--
            .filter {
                // RK --> the rules live in a kernel the novel update job calls too
                val skip = smartUpdateSkip(it.smartUpdateFacts(), restrictions, fetchWindowUpperBound)
                if (skip != null) skippedUpdates.add(it.manga to context.stringResource(skip.reason))
                skip == null
                // RK <--
            }
            .sortedBy { it.manga.title }

        notifier.showQueueSizeWarningNotificationIfNeeded(mangaToUpdate)

        if (skippedUpdates.isNotEmpty()) {
            // TODO: surface skipped reasons to user?
            logcat {
                skippedUpdates
                    .groupBy { it.second }
                    .map { (reason, entries) -> "$reason: [${entries.map { it.first.title }.sorted().joinToString()}]" }
                    .joinToString()
            }
        }
    }

    /**
     * Method that updates manga in [mangaToUpdate]. It's called in a background thread, so it's safe
     * to do heavy operations or network calls here.
     * For each manga it calls [updateManga] and updates the notification showing the current
     * progress.
     *
     * @return an observable delivering the progress of each update.
     */
    // RK --> the run's bookkeeping is shared with the novel update job, in LibraryUpdateRun.kt
    private suspend fun updateChapterList() = libraryUpdateRun<Manga, Chapter>(
        ContentType.MANGA,
        entryId = { it.id },
        chapterId = { it.id },
        announce = { updates ->
            notifier.showUpdateNotifications(updates.map { (manga, chapters) -> manga to chapters.toTypedArray() })
        },
        queueDownloads = { downloads ->
            // Queued five wide, as they were when each entry queued its own inside the run.
            coroutineScope {
                downloads.map { (manga, chapters) -> async { downloadChapters(manga, chapters) } }.awaitAll()
            }
            downloadManager.startDownloads()
        },
    ) { runUpdate(it) }

    private suspend fun runUpdate(ledger: UpdateRunLedger<Manga, Chapter>) {
        // RK <--
        val semaphore = Semaphore(5)
        val progressCount = AtomicInt(0)
        val currentlyUpdatingManga = CopyOnWriteArrayList<Manga>()
        val failedUpdates = CopyOnWriteArrayList<Pair<Manga, String?>>()
        val timeZone = TimeZone.currentSystemDefault()
        val fetchWindow = fetchInterval.getWindow(Clock.System.now().toLocalDateTime(timeZone).date, timeZone)

        coroutineScope {
            mangaToUpdate.groupBy { it.manga.source }.values
                .map { mangaInSource ->
                    async {
                        semaphore.withPermit {
                            mangaInSource.forEach { libraryManga ->
                                val manga = libraryManga.manga
                                ensureActive()

                                // Don't continue to update if manga is not in library
                                if (getManga.await(manga.id)?.favorite != true) {
                                    return@forEach
                                }

                                withUpdateNotification(
                                    currentlyUpdatingManga,
                                    progressCount,
                                    manga,
                                ) {
                                    try {
                                        val newChapters = updateManga(manga, fetchWindow)
                                            .sortedByDescending { it.sourceOrder }

                                        if (newChapters.isNotEmpty()) {
                                            // RK: queued after the run rather than here, so a merge
                                            //     group's sources cannot each fetch the same chapter.
                                            //     Nothing starts downloading mid-run either way.
                                            val chaptersToDownload = filterChaptersForDownload.await(manga, newChapters)
                                            ledger.arrived(manga, newChapters, chaptersToDownload)
                                        }
                                        // RK: a successful check clears any previously recorded error
                                        if (reikaiLibraryPreferences.trackUpdateErrors.get()) {
                                            runCatching { deleteLibraryUpdateErrors.byMangaIds(listOf(manga.id)) }
                                        }
                                    } catch (e: Throwable) {
                                        // RK: the wording is shared with the novel update job
                                        val errorMessage = with(context) { e.updateFailureMessage() }
                                        failedUpdates.add(manga to errorMessage)
                                        // RK: record the failure for the Update errors screen
                                        if (reikaiLibraryPreferences.trackUpdateErrors.get()) {
                                            runCatching {
                                                upsertLibraryUpdateError.await(
                                                    manga.id,
                                                    errorMessage ?: context.stringResource(MR.strings.unknown),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .awaitAll()
        }

        notifier.cancelProgressNotification()

        // RK --> the dump is one file shared with the other updaters, rewritten on every run so an
        //        entry that has since updated stops appearing in it.
        val errorFile = updateErrorLog.write(
            UpdateErrorSection.MANGA,
            failedUpdates.map { (manga, message) ->
                UpdateErrorEntry(
                    title = manga.title,
                    sourceName = sourceManager.getOrStub(manga.source).toString(),
                    message = message ?: context.stringResource(MR.strings.unknown),
                )
            },
        )
        if (failedUpdates.isNotEmpty()) {
            notifier.showUpdateErrorNotification(
                failedUpdates.size,
                errorFile.getUriCompat(context),
                reikaiLibraryPreferences.trackUpdateErrors.get(),
            )
        }
        // RK <--
    }

    private suspend fun downloadChapters(manga: Manga, chapters: List<Chapter>) {
        // We don't want to start downloading while the library is updating, because websites
        // may don't like it and they could ban the user.
        downloadManager.downloadChapters(manga, chapters, false)
    }

    /**
     * Updates the chapters for the given manga and adds them to the database.
     *
     * @param manga the manga to update.
     * @return a pair of the inserted and removed chapters.
     */
    private suspend fun updateManga(manga: Manga, fetchWindow: Pair<Long, Long>): List<Chapter> {
        val source = sourceManager.getOrStub(manga.source)

        val update = updateMangaFromRemote(
            source = source,
            manga = manga,
            fetchDetails = libraryPreferences.autoUpdateMetadata.get(),
            fetchChapters = true,
            fetchWindow = fetchWindow,
        )
            .getOrThrow()

        return if (update.manga.favorite) update.newChapters else emptyList()
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
        completed.incrementAndFetch()
        notifier.showProgressNotification(
            updatingManga,
            completed.load(),
            mangaToUpdate.size,
        )
    }

    // RK: writeErrorFile moved to reikai.data.updateerror.UpdateErrorLog, which every update job writes

    companion object {
        private const val TAG = "LibraryUpdate"
        private const val WORK_NAME_AUTO = "LibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "LibraryUpdate-manual"

        // RK --> the recents surface shows a refreshing state that ends when the job does, and the tag
        //        it needs is private here.
        fun isRunningFlow(context: Context): Flow<Boolean> = context.workRunningFlow(TAG)
        // RK <--

        private const val MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 60

        /**
         * Key for category to update.
         */
        private const val KEY_CATEGORY = "category"

        fun setupTask(
            context: Context,
            prefInterval: Int? = null,
        ) {
            val preferences = context.appGraph.libraryPreferences
            val interval = prefInterval ?: preferences.autoUpdateInterval.get()
            if (interval > 0) {
                val restrictions = preferences.autoUpdateDeviceRestrictions.get()
                // RK: built in LibraryUpdateSchedule.kt, which the novel updater shares
                val request =
                    libraryUpdatePeriodicRequest<LibraryUpdateWorker>(interval, restrictions, TAG, WORK_NAME_AUTO)

                context.workManager.enqueueUniquePeriodicWork(
                    WORK_NAME_AUTO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                context.workManager.cancelUniqueWork(WORK_NAME_AUTO)
            }
        }

        fun startNow(
            workManager: WorkManager,
            category: Category? = null,
        ): Boolean {
            if (workManager.isRunning(TAG)) {
                // Already running either as a scheduled or manual job
                return false
            }

            val inputData = workDataOf(
                KEY_CATEGORY to category?.id,
            )
            // RK: built in LibraryUpdateSchedule.kt, which the novel updater shares
            val request = libraryUpdateManualRequest<LibraryUpdateWorker>(TAG, WORK_NAME_MANUAL, inputData)
            workManager.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)

            return true
        }

        // RK -->

        /**
         * Debug builds only. Enqueues the same manual update [startNow] does, after [delaySeconds],
         * so the app can be killed in between.
         *
         * The point is the process it lands in. Running an update normally keeps a foreground
         * service up, which makes the process unkillable, and force-stopping instead cancels every
         * scheduled job that could have restarted it. Nothing runs during the delay, so the app can
         * be killed there and the job then starts a process with no activity ever created, which is
         * the only way to reach the solver's no-window path on a real trigger.
         */
        fun startDelayed(workManager: WorkManager, delaySeconds: Long) {
            val request = OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.REPLACE, request)
        }
        // RK <--

        // RK: the rule lives in LibraryUpdateSchedule.kt, which the novel updater shares
        fun stop(context: Context) = stopLibraryUpdate(context.workManager, TAG, WORK_NAME_AUTO) { setupTask(context) }
    }
}
