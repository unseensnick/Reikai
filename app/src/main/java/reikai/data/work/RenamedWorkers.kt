package reikai.data.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import eu.kanade.domain.track.service.DelayedTrackingUpdateWorker
import eu.kanade.tachiyomi.data.backup.create.BackupCreateWorker
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.data.download.DownloadWorker
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.data.library.MetadataUpdateWorker
import exh.favorites.EhFavoritesBackupWorker
import exh.md.MangaDexSyncWorker
import reikai.data.novel.update.LnPluginUpdateWorker
import reikai.data.novel.update.NovelUpdateWorker
import reikai.data.recommendation.taste.TrackerLibraryRefreshWorker
import reikai.data.track.TrackerRefreshWorker
import reikai.domain.novel.track.NovelDelayedTrackingUpdateWorker
import reikai.novel.download.NovelDownloadWorker

/**
 * WorkManager stores each queued job's class name, and every `*Job` worker was renamed `*Worker` (mihon
 * db45dda52, which Reikai's own followed). Work queued by an older build still names the old class, which
 * WorkManager would drop as missing (and WorkerStartFailures report); this builds the renamed class instead.
 * Mihon installs no factory and loses that work; see upstream-sync.md "Deliberate divergences".
 */
object RenamedWorkers : WorkerFactory() {

    /** Each worker kept its package and swapped only the suffix, so its old name derives from the new. */
    private val byRetiredName: Map<String, Class<out ListenableWorker>> = listOf(
        DelayedTrackingUpdateWorker::class.java,
        BackupCreateWorker::class.java,
        BackupRestoreWorker::class.java,
        DownloadWorker::class.java,
        LibraryUpdateWorker::class.java,
        MetadataUpdateWorker::class.java,
        EhFavoritesBackupWorker::class.java,
        MangaDexSyncWorker::class.java,
        LnPluginUpdateWorker::class.java,
        NovelUpdateWorker::class.java,
        TrackerLibraryRefreshWorker::class.java,
        TrackerRefreshWorker::class.java,
        NovelDelayedTrackingUpdateWorker::class.java,
        NovelDownloadWorker::class.java,
    ).associateBy { it.name.removeSuffix("Worker") + "Job" }

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = byRetiredName[workerClassName]
        ?.getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
        ?.newInstance(appContext, workerParameters)
}
