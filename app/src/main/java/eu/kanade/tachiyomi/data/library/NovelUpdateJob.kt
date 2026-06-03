package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.DEVICE_BATTERY_NOT_LOW
import eu.kanade.tachiyomi.data.preference.DEVICE_CHARGING
import eu.kanade.tachiyomi.data.preference.DEVICE_ONLY_ON_WIFI
import eu.kanade.tachiyomi.data.preference.MANGA_HAS_UNREAD
import eu.kanade.tachiyomi.data.preference.MANGA_NON_COMPLETED
import eu.kanade.tachiyomi.data.preference.MANGA_NON_READ
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithNovelSource
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.tryToSetForeground
import eu.kanade.tachiyomi.util.system.withIOContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.Date
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.mp.KoinPlatform
import yokai.data.DatabaseHandler
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.domain.novel.models.Novel
import yokai.domain.novel.models.NovelChapter
import yokai.i18n.MR
import yokai.novel.host.ChapterItem
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSource
import yokai.novel.source.NovelSourceManager
import yokai.util.lang.getString

/**
 * Novel-side parallel of [LibraryUpdateJob]. Iterates favorited novels, fetches each one's
 * chapter list from its plugin, syncs via [syncChaptersWithNovelSource], and reports progress
 * + results through [NovelUpdateNotifier].
 *
 * Differences from the manga job (all locked by Phase 7 decisions):
 *
 * * `Target.DETAILS` and `Target.TRACKING` are dropped. Novels currently only have one update
 *   target (CHAPTERS) — detail / cover refresh isn't wired (no novel `CoverCache` yet), and
 *   tracker reconciliation is deferred per Decision #5.
 * * Downloads are stubbed per Decision #4 — `downloadChapters()` is a no-op log.
 * * Scanlator filtering is absent throughout — novels have no scanlators per Decision #1.
 * * Merge-group expansion (`relatedNovelIds`) is not used — no novel merge yet.
 * * Source IDs are `String` (lnreader plugin id), not `Long`, so `novelToUpdateMap` is keyed
 *   by String.
 *
 * Net-new: the paged-novel refresh loop in [updateNovelChapters] reads
 * [yokai.novel.host.SourceNovel.totalPages] and (for paged sources) calls
 * [NovelSource.parsePage] across `oldTotalPages..newTotalPages` to catch chapters added to
 * historical pages plus everything on new pages. Mirrors lnreader's pattern at
 * `services/updates/index.ts:199-235`.
 */
class NovelUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val getNovelCategories: GetNovelCategories by inject()
    private val novelRepository: NovelRepository by inject()
    private val novelChapterRepository: NovelChapterRepository by inject()
    private val novelSourceManager: NovelSourceManager by inject()
    private val installer: LnPluginInstaller by inject()
    private val novelPreferences: NovelPreferences by inject()
    private val handler: DatabaseHandler by inject()

    private var extraDeferredJobs = mutableListOf<Deferred<Any>>()

    private val extraScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val emitScope = MainScope()

    private val novelToUpdate = mutableListOf<LibraryNovel>()

    private val novelToUpdateMap = mutableMapOf<String, List<LibraryNovel>>()

    private val categoryIds = mutableSetOf<Int>()

    private val newUpdates = mutableMapOf<LibraryNovel, Array<NovelChapter>>()

    private val failedUpdates = mutableMapOf<Novel, String?>()

    private val skippedUpdates = mutableMapOf<Novel, String?>()

    val count = AtomicInteger(0)

    private val requestSemaphore = Semaphore(5)

    private val deleteRemoved by lazy { novelPreferences.deleteRemovedChapters().get() != 1 }

    private val notifier = NovelUpdateNotifier(context.localeContext)

    override suspend fun doWork(): Result {
        if (tags.contains(WORK_NAME_AUTO)) {
            val restrictions = novelPreferences.libraryUpdateDeviceRestriction().get()
            if ((DEVICE_ONLY_ON_WIFI in restrictions) && !context.isConnectedToWifi()) {
                return Result.failure()
            }

            // Find a running manual worker. If exists, try again later.
            if (instance != null) {
                return Result.retry()
            }
        }

        tryToSetForeground()

        instance = WeakReference(this)

        novelPreferences.libraryUpdateLastTimestamp().set(Date().time)

        val savedNovelsList = inputData.getLongArray(KEY_NOVELS)?.asList()?.plus(extraNovels)
        extraNovels = emptyList()

        val novelList = (
            if (savedNovelsList != null) {
                val novels = novelRepository.getLibraryNovel()
                    .filter { it.novel.id in savedNovelsList }
                    .distinctBy { it.novel.id }
                val categoryId = inputData.getInt(KEY_CATEGORY, -1)
                // Use `!= -1` (the sentinel returned when KEY_CATEGORY was not set in inputData)
                // rather than `> -1`. The manga side learned this in Phase 6 C10: the original
                // `> -1` check accidentally excluded synthetic dynamic category ids (negative
                // values like -2, -3 produced by dynamic grouping), which meant their headers
                // never spun while a dynamic refresh was running from idle. Real-category ids
                // (>= 0) are unaffected.
                if (categoryId != -1) categoryIds.add(categoryId)
                novels
            } else {
                getNovelsToUpdate()
            }
            ).sortedBy { it.novel.title }

        return withIOContext {
            try {
                // Populate NovelSourceManager from the app-scoped host so updateNovelsInSource finds
                // the source in a cold process (background update with no LN screen opened this
                // launch). Idempotent: a no-op if a screen already loaded the plugins.
                installer.ensureLoaded()
                runChapterUpdates(filterNovelsToUpdate(novelList))
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    finishUpdates(true)
                    Result.success()
                } else {
                    Logger.e(e) { "Failed to update novel library" }
                    Result.failure()
                }
            } finally {
                instance = null
                sendUpdate(null)
                notifier.cancelProgressNotification()
            }
        }
    }

    private suspend fun sendUpdate(novelId: Long?) {
        if (isStopped) {
            updateMutableFlow.tryEmit(novelId)
        } else {
            emitScope.launch { updateMutableFlow.emit(novelId) }
        }
    }

    private suspend fun runChapterUpdates(novelToAdd: List<LibraryNovel>) {
        sendUpdate(STARTING_UPDATE_SOURCE)
        novelToUpdate.addAll(novelToAdd)
        novelToUpdateMap.putAll(novelToAdd.groupBy { it.novel.source })
        checkIfMassiveUpdate()
        coroutineScope {
            val list = novelToUpdateMap.keys.map { source ->
                async {
                    try {
                        requestSemaphore.withPermit { updateNovelsInSource(source) }
                    } catch (e: Exception) {
                        Logger.e(e) { "Unable to update novel" }
                        false
                    }
                }
            }
            list.awaitAll()
            finishUpdates()
        }
    }

    private suspend fun finishUpdates(wasStopped: Boolean = false) {
        if (!wasStopped && !isStopped) {
            extraDeferredJobs.awaitAll()
        }
        if (newUpdates.isNotEmpty()) {
            notifier.showResultNotification(newUpdates)
        }
        newUpdates.clear()
        if (skippedUpdates.isNotEmpty() &&
            Notifications.isNotificationChannelEnabled(context, Notifications.CHANNEL_NOVEL_LIBRARY_SKIPPED)
        ) {
            val skippedFile = writeErrorFile(
                skippedUpdates,
                "skipped",
                context.getString(MR.strings.learn_why) + " - " + NovelUpdateNotifier.HELP_SKIPPED_URL,
            ).getUriCompat(context)
            notifier.showUpdateSkippedNotification(skippedUpdates.map { it.key.title }, skippedFile)
        }
        if (failedUpdates.isNotEmpty() &&
            Notifications.isNotificationChannelEnabled(context, Notifications.CHANNEL_NOVEL_LIBRARY_ERROR)
        ) {
            val errorFile = writeErrorFile(failedUpdates).getUriCompat(context)
            notifier.showUpdateErrorNotification(failedUpdates.map { it.key.title }, errorFile)
        }
        failedUpdates.clear()
        notifier.cancelProgressNotification()
    }

    private fun checkIfMassiveUpdate() {
        val largestSourceSize = novelToUpdate
            .groupBy { it.novel.source }
            .maxOfOrNull { it.value.size } ?: 0
        if (largestSourceSize > NOVEL_PER_SOURCE_QUEUE_WARNING_THRESHOLD) {
            notifier.showQueueSizeWarningNotification()
        }
    }

    private suspend fun updateNovelsInSource(source: String): Boolean {
        if (novelToUpdateMap[source] == null) return false
        var index = 0
        val sourceObj = novelSourceManager.get(source) ?: return false
        while (index < novelToUpdateMap[source]!!.size) {
            val libraryNovel = novelToUpdateMap[source]!![index]
            updateNovelChapters(libraryNovel, this.count.andIncrement, sourceObj)
            index++
        }
        novelToUpdateMap[source] = emptyList()
        return false
    }

    /**
     * Fetches a single novel's chapter list (honoring the lnreader paged-novel pattern) and
     * delegates the diff / insert / update / delete to [syncChaptersWithNovelSource].
     *
     * For sources whose chapter lists span multiple pages (`totalPages > 1` and the plugin
     * overrides `parsePage`), this refetches the old last page (to catch chapters added there
     * after our last visit) and then walks forward to the new `totalPages`. Returns the union
     * of all pages' chapters to the sync helper. Mirrors lnreader's logic at
     * `refs/lnreader-main/src/services/updates/index.ts:199-235`.
     */
    private suspend fun updateNovelChapters(
        libraryNovel: LibraryNovel,
        progress: Int,
        source: NovelSource,
    ): Boolean = coroutineScope {
        try {
            ensureActive()
            notifier.showProgressNotification(libraryNovel.novel, progress, novelToUpdate.size)

            val novel = libraryNovel.novel
            val sourceNovel = source.parseNovel(novel.url)
            val accumulated = mutableListOf<ChapterItem>()
            accumulated.addAll(sourceNovel.chapters.orEmpty())

            val oldTotalPages = novel.totalPages
            val newTotalPages = sourceNovel.totalPages
            if (newTotalPages > 1) {
                // Re-fetch the previously-last page so newly-added chapters on it surface.
                if (oldTotalPages > 1) {
                    source.parsePage(novel.url, oldTotalPages.toString())
                        ?.chapters
                        ?.let(accumulated::addAll)
                }
                // Walk forward across pages we've never visited.
                for (page in (oldTotalPages + 1)..newTotalPages) {
                    ensureActive()
                    source.parsePage(novel.url, page.toString())
                        ?.chapters
                        ?.let(accumulated::addAll)
                }
            }

            if (accumulated.isNotEmpty()) {
                val syncResult = syncChaptersWithNovelSource(
                    accumulated,
                    novel,
                    source,
                    novelChapterRepository,
                    novelRepository,
                    handler,
                )
                val newChapters = syncResult.first
                val removedChapters = syncResult.second

                // Persist any change in totalPages so the next refresh's `oldTotalPages` is right.
                if (newTotalPages != oldTotalPages) {
                    novelRepository.update(novel.copy(totalPages = newTotalPages))
                }

                if (newChapters.isNotEmpty()) {
                    downloadChapters(novel, newChapters.sortedBy { it.chapterNumber })
                    newUpdates[libraryNovel] =
                        newChapters.sortedBy { it.chapterNumber }.toTypedArray()
                }
                if (newChapters.size + removedChapters.size > 0) {
                    sendUpdate(novel.id)
                }
            }
            return@coroutineScope false
        } catch (e: Exception) {
            if (e !is CancellationException) {
                failedUpdates[libraryNovel.novel] = e.message
                Logger.e { "Failed updating novel: ${libraryNovel.novel.title}: $e" }
            }
            return@coroutineScope false
        }
    }

    /**
     * Stub. Real novel downloads are deferred per Phase 7 Decision #4; this method exists so
     * the call site reads symmetrically to the manga side and so wiring up downloads later
     * doesn't require an `updateNovelChapters` patch.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun downloadChapters(novel: Novel, chapters: List<NovelChapter>) {
        // No-op until novel downloads ship.
    }

    private fun filterNovelsToUpdate(novelToAdd: List<LibraryNovel>): List<LibraryNovel> {
        val restrictions = novelPreferences.libraryUpdateNovelRestriction().get()
        return novelToAdd.filter { libraryNovel ->
            val novel = libraryNovel.novel
            when {
                MANGA_NON_COMPLETED in restrictions && novel.status == STATUS_COMPLETED -> {
                    skippedUpdates[novel] = context.getString(MR.strings.skipped_reason_completed)
                }
                MANGA_HAS_UNREAD in restrictions && libraryNovel.unread != 0 -> {
                    skippedUpdates[novel] = context.getString(MR.strings.skipped_reason_not_caught_up)
                }
                MANGA_NON_READ in restrictions &&
                    libraryNovel.totalChapters > 0 && !libraryNovel.hasRead -> {
                    skippedUpdates[novel] = context.getString(MR.strings.skipped_reason_not_started)
                }
                novel.updateStrategy != UpdateStrategy.ALWAYS_UPDATE.ordinal -> {
                    skippedUpdates[novel] = context.getString(MR.strings.skipped_reason_not_always_update)
                }
                else -> return@filter true
            }
            false
        }
    }

    private suspend fun getNovelsToUpdate(): List<LibraryNovel> {
        val categoryId = inputData.getInt(KEY_CATEGORY, -1)
        return getNovelsToUpdate(categoryId)
    }

    private suspend fun getNovelsToUpdate(categoryId: Int): List<LibraryNovel> {
        val libraryNovels = novelRepository.getLibraryNovel()

        val listToUpdate = if (categoryId != -1) {
            categoryIds.add(categoryId)
            libraryNovels.filter { it.category == categoryId }
        } else {
            val categoriesToUpdate = novelPreferences.libraryUpdateCategories().get().map(String::toInt)
            if (categoriesToUpdate.isNotEmpty()) {
                categoryIds.addAll(categoriesToUpdate)
                libraryNovels.filter { it.category in categoriesToUpdate }.distinctBy { it.novel.id }
            } else {
                categoryIds.addAll(getNovelCategories.await().mapNotNull { it.id } + 0)
                libraryNovels.distinctBy { it.novel.id }
            }
        }

        val categoriesToExclude =
            novelPreferences.libraryUpdateCategoriesExclude().get().map(String::toInt)
        val listToExclude = if (categoriesToExclude.isNotEmpty() && categoryId == -1) {
            libraryNovels.filter { it.category in categoriesToExclude }.toSet()
        } else {
            emptySet()
        }

        return listToUpdate.minus(listToExclude)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progressNotificationBuilder.build()
        val id = Notifications.ID_NOVEL_LIBRARY_PROGRESS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    /**
     * Writes a basic file of update errors to the cache dir.
     */
    private fun writeErrorFile(
        errors: Map<Novel, String?>,
        fileName: String = "errors",
        additionalInfo: String? = null,
    ): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("reikai_novel_update_$fileName.txt")
                file.bufferedWriter().use { out ->
                    additionalInfo?.let { out.write("$it\n\n") }
                    errors.toList().groupBy({ it.second }, { it.first }).forEach { (error, novels) ->
                        out.write("! ${error}\n")
                        novels.groupBy { it.source }.forEach { (srcId, novels) ->
                            val sourceName = novelSourceManager.get(srcId)?.name ?: srcId
                            out.write("  # $sourceName\n")
                            novels.forEach { out.write("    - ${it.title}\n") }
                        }
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // ignore
        }
        return File("")
    }

    private fun addNovelToQueue(categoryId: Int, novels: List<LibraryNovel>) {
        val toQueue = filterNovelsToUpdate(novels).sortedBy { it.novel.title }
        categoryIds.add(categoryId)
        addNovel(toQueue)
    }

    private fun addCategory(categoryId: Int) {
        val toQueue =
            filterNovelsToUpdate(runBlocking { getNovelsToUpdate(categoryId) }).sortedBy { it.novel.title }
        categoryIds.add(categoryId)
        addNovel(toQueue)
    }

    private fun addNovel(novelToAdd: List<LibraryNovel>) {
        val distinct = novelToAdd.filter { it !in novelToUpdate }
        novelToUpdate.addAll(distinct)
        checkIfMassiveUpdate()
        distinct.groupBy { it.novel.source }.forEach { (sourceId, list) ->
            if (novelToUpdateMap[sourceId].isNullOrEmpty()) {
                novelToUpdateMap[sourceId] = list
                extraScope.launch {
                    extraDeferredJobs.add(
                        async(Dispatchers.IO) {
                            try {
                                requestSemaphore.withPermit { updateNovelsInSource(sourceId) }
                            } catch (_: Exception) {
                                false
                            }
                        },
                    )
                }
            } else {
                val existing = novelToUpdateMap[sourceId] ?: emptyList()
                novelToUpdateMap[sourceId] = (existing + list)
            }
        }
    }

    companion object {
        private const val TAG = "NovelLibraryUpdate"
        private const val WORK_NAME_AUTO = "NovelLibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "NovelLibraryUpdate-manual"

        private const val NOVEL_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 60

        /** Sentinel matching NovelStatusCode.COMPLETED so we don't depend on its import. */
        private const val STATUS_COMPLETED = 2

        private const val KEY_CATEGORY = "category"
        const val STARTING_UPDATE_SOURCE = -5L

        private const val KEY_NOVELS = "novels"

        private var instance: WeakReference<NovelUpdateJob>? = null

        private var extraNovels = emptyList<Long>()

        val updateMutableFlow = MutableSharedFlow<Long?>(
            extraBufferCapacity = 10,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val updateFlow = updateMutableFlow.asSharedFlow()

        fun setupTask(context: Context, prefInterval: Int? = null) {
            // Per Decision #2 the novel auto-update cadence is independent of the manga one:
            // read from NovelPreferences, never from PreferencesHelper. NovelPreferences is
            // Koin-only (see PreferenceModule:41), so static access goes through KoinPlatform.
            val preferences = KoinPlatform.getKoin().get<NovelPreferences>()
            val interval = prefInterval ?: preferences.libraryUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.libraryUpdateDeviceRestriction().get()

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresCharging(DEVICE_CHARGING in restrictions)
                    .setRequiresBatteryNotLow(DEVICE_BATTERY_NOT_LOW in restrictions)
                    .build()

                val request = PeriodicWorkRequestBuilder<NovelUpdateJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG)
                    .addTag(WORK_NAME_AUTO)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME_AUTO,
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    request,
                )
            } else {
                WorkManager.getInstance(context).cancelAllWorkByTag(WORK_NAME_AUTO)
            }
        }

        fun cancelAllWorks(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { list ->
                list.any { it.state == WorkInfo.State.RUNNING }
            }
        }

        fun isRunning(context: Context): Boolean {
            val list = WorkManager.getInstance(context).getWorkInfosByTag(TAG).get()
            return list.any { it.state == WorkInfo.State.RUNNING }
        }

        fun categoryInQueue(id: Int?) = instance?.get()?.categoryIds?.contains(id) ?: false

        fun startNow(
            context: Context,
            category: NovelCategory? = null,
            novelsToUse: List<LibraryNovel>? = null,
        ): Boolean {
            if (isRunning(context)) {
                category?.id?.let {
                    if (novelsToUse != null) {
                        instance?.get()?.addNovelToQueue(it, novelsToUse)
                    } else {
                        instance?.get()?.addCategory(it)
                    }
                }
                return false
            }

            val builder = Data.Builder()
            category?.id?.let { id ->
                builder.putInt(KEY_CATEGORY, id)
                if (novelsToUse != null) {
                    builder.putLongArray(
                        KEY_NOVELS,
                        novelsToUse.firstOrNull()?.novel?.id?.let { longArrayOf(it) } ?: longArrayOf(),
                    )
                    extraNovels = novelsToUse.subList(1, novelsToUse.size).mapNotNull { it.novel.id }
                }
            }
            val inputData = builder.build()
            val request = OneTimeWorkRequestBuilder<NovelUpdateJob>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)

            return true
        }

        fun stop(context: Context) {
            val wm = WorkManager.getInstance(context)
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
