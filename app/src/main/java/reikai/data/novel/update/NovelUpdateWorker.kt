package reikai.data.novel.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import mihon.core.migration.Migrator
import reikai.data.library.EntryCheck
import reikai.data.library.LibraryUpdateRun
import reikai.data.library.UpdateRunLedger
import reikai.data.library.checkUpdateEntry
import reikai.data.library.libraryUpdateManualRequest
import reikai.data.library.libraryUpdatePeriodicRequest
import reikai.data.library.shouldDeferLibraryUpdate
import reikai.data.library.stopLibraryUpdate
import reikai.data.novel.refreshNovelFromSource
import reikai.data.updateerror.UpdateErrorEntry
import reikai.data.updateerror.UpdateErrorLog
import reikai.data.updateerror.UpdateErrorSection
import reikai.data.updateerror.updateFailureMessage
import reikai.domain.category.isUpdateScope
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.ReleaseInterval
import reikai.domain.library.smartUpdateFacts
import reikai.domain.library.smartUpdateSkip
import reikai.domain.manga.AdultContentChecker
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.FilterNovelChaptersForDownload
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.updateerror.DeleteNovelUpdateErrors
import reikai.domain.novel.updateerror.UpsertNovelUpdateError
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.util.runCatchingCancellable
import reikai.util.workRunningFlow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import kotlin.time.Clock

/**
 * Periodic background check for new chapters in favorited light novels, the novel analog of Mihon's
 * [eu.kanade.tachiyomi.data.library.LibraryUpdateWorker]: a configurable WorkManager schedule that
 * re-parses each favorite, syncs its chapter list, optionally auto-downloads the new chapters, and
 * posts progress and result notifications. Per-novel logic is the shared [refreshNovelFromSource], the
 * same helper the details refresh uses; new chapters are what its syncs report as new.
 */
class NovelUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    init {
        graph.inject(this)
    }

    @Inject private lateinit var novelRepo: NovelRepository

    @Inject private lateinit var chapterRepo: NovelChapterRepository

    @Inject private lateinit var downloadManager: NovelDownloadManager

    @Inject private lateinit var sourceManager: NovelSourceManager

    @Inject private lateinit var installer: LnPluginInstaller

    @Inject private lateinit var filterChaptersForDownload: FilterNovelChaptersForDownload

    @Inject private lateinit var preferences: NovelPreferences

    @Inject private lateinit var libraryPreferences: LibraryPreferences

    @Inject private lateinit var chapterNumberOverrides: ChapterNumberOverrideRepository

    @Inject private lateinit var coverCache: CoverCache

    @Inject private lateinit var reikaiLibraryPreferences: ReikaiLibraryPreferences

    @Inject private lateinit var upsertNovelUpdateError: UpsertNovelUpdateError

    @Inject private lateinit var deleteNovelUpdateErrors: DeleteNovelUpdateErrors
    private val updateErrorLog = UpdateErrorLog(context)

    @Inject private lateinit var libraryUpdateRun: LibraryUpdateRun

    @Inject private lateinit var securityPreferences: SecurityPreferences

    @Inject private lateinit var adultChecker: AdultContentChecker
    private val notifier = NovelUpdateNotifier(context, securityPreferences, adultChecker)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progress(null, 0, 0)
        val id = Notifications.ID_NOVEL_LIBRARY_PROGRESS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    override suspend fun doWork(): Result {
        // A WorkManager start has no MainActivity to wait for the migrations first
        Migrator.await()
        val restrictions = preferences.libraryUpdateDeviceRestrictions().get()
        if (shouldDeferLibraryUpdate(restrictions, WORK_NAME_AUTO, WORK_NAME_MANUAL)) return Result.retry()
        setForegroundSafely()
        // Stamp the run start for the Updates "Last updated" line (matches manga LibraryUpdateWorker).
        preferences.novelLibraryUpdateLastTimestamp().set(System.currentTimeMillis())
        return try {
            val categoryId = inputData.getLong(KEY_CATEGORY, -1L)
            withIOContext { updateNovels(categoryId) }
            Result.success()
        } catch (_: CancellationException) {
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.failure()
        } finally {
            notifier.dismissProgress()
        }
    }

    private suspend fun updateNovels(categoryId: Long) = libraryUpdateRun<Novel, NovelChapter>(
        ContentType.NOVELS,
        entryId = { it.id },
        chapterId = { it.id },
        announce = { notifier.showResults(it) },
        queueDownloads = { downloads -> downloadManager.downloadChapters(downloads.flatMap { it.second }) },
    ) { runUpdate(categoryId, it) }

    private suspend fun runUpdate(categoryId: Long, ledger: UpdateRunLedger<Novel, NovelChapter>) {
        // One load brings every installed plugin into the host; per-novel resolution is then cheap.
        runCatchingCancellable { installer.ensureLoaded() }
            .onFailure { logcat(LogPriority.ERROR, it) { "Could not load the novel plugins" } }

        // Smart-update restrictions apply to a manual update of one category too, matching manga.
        val trackErrors = reikaiLibraryPreferences.trackNovelUpdateErrors.get()
        val timeZone = TimeZone.currentSystemDefault()
        val fetchWindow = ReleaseInterval.window(Clock.System.now().toLocalDateTime(timeZone).date, timeZone)
        val restrictions = preferences.novelUpdateRestrictions().get()
        val include = preferences.novelUpdateCategories().get().mapTo(mutableSetOf()) { it.toLong() }
        val exclude = preferences.novelUpdateCategoriesExclude().get().mapTo(mutableSetOf()) { it.toLong() }
        // The library rows carry the categories (an uncategorized novel as Default, 0, as the manga job
        // reads them) and the chapter counts the rules read, so nothing else is loaded to decide. In
        // title order, as the manga job runs.
        val favorites = novelRepo.getLibraryNovelAsFlow().first()
            .filter { entry ->
                isUpdateScope(entry.categories, categoryId, include, exclude) &&
                    smartUpdateSkip(entry.smartUpdateFacts(), restrictions, fetchWindow.second) == null
            }
            .sortedBy { it.novel.title }
            .map { it.novel }
        if (favorites.isEmpty()) return

        val failed = mutableListOf<UpdateErrorEntry>()
        favorites.forEachIndexed { index, novel ->
            currentCoroutineContext().ensureActive()
            val outcome = checkUpdateEntry(
                stillInLibrary = { novelRepo.getById(novel.id)?.favorite == true },
                // [index] is how many are already done, which is what the bar reports while this one runs;
                // again once it is done, so the bar actually reaches its end.
                progress = { check ->
                    notifier.showProgress(novel, index, favorites.size)
                    check()
                    notifier.showProgress(novel, index + 1, favorites.size)
                },
                trackErrors = trackErrors,
                clearError = { deleteNovelUpdateErrors.byNovelIds(listOf(novel.id)) },
                recordError = { upsertNovelUpdateError.await(novel.id, it) },
                failureMessage = { with(context) { it.updateFailureMessage() } },
            ) {
                val newChapters = checkNovel(novel, sourceManager.getOrThrow(novel.source), fetchWindow)
                if (newChapters.isNotEmpty()) {
                    // Queued after the run rather than here, so a merge group's sources cannot each
                    // fetch the same chapter.
                    ledger.arrived(novel, newChapters, filterChaptersForDownload.await(novel, newChapters))
                }
            }
            if (outcome is EntryCheck.Failed) {
                failed += UpdateErrorEntry(novel.title, sourceManager.nameOf(novel.source), outcome.message)
            }
        }
        // The dump is one file shared with the other updaters, rewritten on every run so a novel that
        // has since updated stops appearing in it.
        val errorFile = updateErrorLog.write(UpdateErrorSection.NOVELS, failed)
        if (failed.isNotEmpty()) {
            notifier.showUpdateErrors(failed.size, errorFile.getUriCompat(context), trackErrors)
        }
    }

    /** Re-parse the novel, store the source's metadata, sync page 1, and walk any newly-opened
     *  pages. Returns the chapters the syncs report as new, which leaves out a duplicate marked read
     *  and a re-listed chapter, as the manga job's sync result does. */
    private suspend fun checkNovel(
        novel: Novel,
        source: NovelSource,
        fetchWindow: Pair<Long, Long>,
    ): List<NovelChapter> = refreshNovelFromSource(
        novel,
        source,
        chapterRepo,
        novelRepo,
        libraryPreferences,
        chapterNumberOverrides,
        coverCache,
        novelDownloadManager = downloadManager,
        fetchWindow = fetchWindow,
    ).newChapters

    companion object {
        private const val TAG = "NovelLibraryUpdate"
        private const val WORK_NAME_AUTO = "NovelLibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "NovelLibraryUpdate-manual"
        private const val KEY_CATEGORY = "category"

        /** Twin of `LibraryUpdateWorker.isRunningFlow`, pinned by [workRunningFlow], the one rule both read. */
        fun isRunningFlow(context: Context): Flow<Boolean> = context.workRunningFlow(TAG)

        /** (Re)schedule or cancel the periodic check from the stored interval (0 = off). Idempotent. */
        fun setupTask(context: Context, prefInterval: Int? = null) {
            val preferences = context.appGraph.novelPreferences
            val interval = prefInterval ?: preferences.libraryUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.libraryUpdateDeviceRestrictions().get()
                val request =
                    libraryUpdatePeriodicRequest<NovelUpdateWorker>(interval, restrictions, TAG, WORK_NAME_AUTO)
                context.workManager.enqueueUniquePeriodicWork(
                    WORK_NAME_AUTO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                context.workManager.cancelUniqueWork(WORK_NAME_AUTO)
            }
        }

        /** Run a check immediately (for manual triggers / testing); reuses a running drain via KEEP.
         *  A non-null [category] scopes the run to that category; null updates the whole library
         *  per the include/exclude prefs. */
        fun startNow(workManager: WorkManager, category: Category? = null): Boolean {
            val wm = workManager
            if (wm.isRunning(TAG)) {
                // Already running either as a scheduled or manual job.
                return false
            }
            val inputData = workDataOf(KEY_CATEGORY to (category?.id ?: -1L))
            val request = libraryUpdateManualRequest<NovelUpdateWorker>(TAG, WORK_NAME_MANUAL, inputData)
            wm.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)
            return true
        }

        fun stop(context: Context) = stopLibraryUpdate(context.workManager, TAG, WORK_NAME_AUTO) { setupTask(context) }
    }
}
