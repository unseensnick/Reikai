package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.core.migration.Migrator
import reikai.data.backup.ApprovedRepos
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

class BackupRestoreWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var backupRestorerFactory: BackupRestorer.Factory

    @Inject private lateinit var notifier: BackupNotifier

    override suspend fun doWork(): Result {
        Migrator.await() // RK: a WorkManager start has no MainActivity to wait for the migrations first
        graph.inject(this)

        val uri = inputData.getString(LOCATION_URI_KEY)?.toUri()
        val options = inputData.getBooleanArray(OPTIONS_KEY)?.let { RestoreOptions.fromBooleanArray(it) }

        if (uri == null || options == null) {
            return Result.failure()
        }

        val isSync = inputData.getBoolean(SYNC_KEY, false)
        // RK: the backup repos the user trusted on the Restore screen
        val trusted = inputData.getString(TRUSTED_REPOS_KEY)?.let { Json.decodeFromString<ApprovedRepos>(it) }
            ?: ApprovedRepos()

        setForegroundSafely()

        return try {
            withIOContext {
                backupRestorerFactory.create(notifier = notifier, isSync = isSync).restore(uri, options, trusted) // RK
            }
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) {
                notifier.showRestoreError(context.stringResource(MR.strings.restoring_backup_canceled))
                Result.success()
            } else {
                logcat(LogPriority.ERROR, e)
                notifier.showRestoreError(e.message)
                Result.failure()
            }
        } finally {
            context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_RESTORE_PROGRESS,
            notifier.showRestoreProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        suspend fun isRunning(workManager: WorkManager): Boolean {
            return workManager.isRunning(TAG)
        }

        fun start(
            workManager: WorkManager,
            uri: Uri,
            options: RestoreOptions,
            sync: Boolean = false,
            trusted: ApprovedRepos = ApprovedRepos(), // RK
        ) {
            val inputData = workDataOf(
                LOCATION_URI_KEY to uri.toString(),
                SYNC_KEY to sync,
                OPTIONS_KEY to options.asBooleanArray(),
                TRUSTED_REPOS_KEY to Json.encodeToString(trusted), // RK
            )
            val request = OneTimeWorkRequestBuilder<BackupRestoreWorker>()
                .addTag(TAG)
                .setInputData(inputData)
                .build()
            workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(workManager: WorkManager) {
            workManager.cancelUniqueWork(TAG)
        }
    }
}

private const val TAG = "BackupRestore"

private const val LOCATION_URI_KEY = "location_uri" // String
private const val SYNC_KEY = "sync" // Boolean
private const val OPTIONS_KEY = "options" // BooleanArray
private const val TRUSTED_REPOS_KEY = "trusted_repos" // RK: String, ApprovedRepos as JSON
