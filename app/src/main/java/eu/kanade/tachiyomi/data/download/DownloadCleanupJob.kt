package eu.kanade.tachiyomi.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.e
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.workManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.manga.interactor.GetManga
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Removes unused downloaded chapters (and, optionally, read chapters and downloads of
 * non-favorite series). Runs as a [CoroutineWorker] rather than on the screen scope because the
 * walk over every source's on-disk folders can outlive the settings screen the user launched it
 * from. Reports the cleared-folder count via a completion notification (a worker can't toast).
 */
class DownloadCleanupJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val getManga: GetManga = Injekt.get()
    private val getChapter: GetChapter = Injekt.get()
    private val downloadManager: DownloadManager = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()

    override suspend fun doWork(): Result {
        val removeRead = inputData.getBoolean(REMOVE_READ_KEY, false)
        val removeNonFavorite = inputData.getBoolean(REMOVE_NON_FAVORITE_KEY, false)

        return try {
            val mangaList = getManga.awaitAll()
            val downloadProvider = DownloadProvider(context)
            var foldersCleared = 0

            for (source in sourceManager.getOnlineSources()) {
                val mangaFolders = downloadManager.getMangaFolders(source)
                val sourceManga = mangaList.filter { it.source == source.id }

                for (mangaFolder in mangaFolders) {
                    val manga = sourceManga.find { downloadProvider.getMangaDirName(it) == mangaFolder.name }
                    if (manga == null) {
                        if (removeNonFavorite) {
                            foldersCleared += 1 + (mangaFolder.listFiles()?.size ?: 0)
                            mangaFolder.delete()
                        }
                        continue
                    }
                    val chapterList = getChapter.awaitAll(manga, false)
                    foldersCleared += downloadManager.cleanupChapters(chapterList, manga, source, removeRead, removeNonFavorite)
                }
            }

            showCompleteNotification(foldersCleared)
            Result.success()
        } catch (e: Exception) {
            Logger.e(e) { "Unable to clean up downloads" }
            Result.failure()
        }
    }

    private fun showCompleteNotification(foldersCleared: Int) {
        val text = if (foldersCleared == 0) {
            context.getString(MR.strings.no_folders_to_cleanup)
        } else {
            context.getString(MR.plurals.cleanup_done, foldersCleared, foldersCleared)
        }
        val notification = context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER) {
            setSmallIcon(R.drawable.ic_yokai)
            setContentText(text)
            setAutoCancel(true)
        }.build()
        context.notificationManager.notify(Notifications.ID_DOWNLOAD_CLEANUP_COMPLETE, notification)
    }

    companion object {
        private const val WORK_NAME = "DownloadCleanup"
        private const val REMOVE_READ_KEY = "remove_read"
        private const val REMOVE_NON_FAVORITE_KEY = "remove_non_favorite"

        fun startNow(context: Context, removeRead: Boolean, removeNonFavorite: Boolean) {
            val request = OneTimeWorkRequestBuilder<DownloadCleanupJob>()
                .addTag(WORK_NAME)
                .setInputData(
                    workDataOf(
                        REMOVE_READ_KEY to removeRead,
                        REMOVE_NON_FAVORITE_KEY to removeNonFavorite,
                    ),
                )
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
