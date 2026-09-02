package eu.kanade.tachiyomi.extension.installer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.CallSuper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.util.Collections
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Base implementation class for extension installer. To be used inside a foreground [Service].
 */
@OptIn(ExperimentalAtomicApi::class)
abstract class Installer(private val service: Service) {

    private val extensionManager: ExtensionManager by injectLazy()

    private var waitingInstall = AtomicReference<Entry?>(null)

    // RK: a set (not a list) so an extension can't get queued twice (from Komikku 94eac94ce7).
    private val queue = Collections.synchronizedSet(mutableSetOf<Entry>())

    // RK: on the main looper rather than a View's queue, which only drains while attached.
    private val stallHandler = Handler(Looper.getMainLooper())

    private val giveUp = Runnable {
        logcat(LogPriority.WARN) { "Install stalled with no answer; stopping the queue" }
        service.stopSelf()
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1).takeIf { it >= 0 } ?: return
            cancelQueue(downloadId)
        }
    }

    /**
     * Installer readiness. If false, queue check will not run.
     *
     * @see checkQueue
     */
    abstract var ready: Boolean

    /**
     * Add an item to install queue.
     *
     * @param downloadId Download ID as known by [ExtensionManager]
     * @param uri Uri of APK to install
     */
    fun addToQueue(downloadId: Long, uri: Uri) {
        queue.add(Entry(downloadId, uri))
        checkQueue()
    }

    /**
     * Proceeds to install the APK of this entry inside this method. Call [continueQueue]
     * when the install process for this entry is finished to continue the queue.
     *
     * @param entry The [Entry] of item to process
     * @see continueQueue
     */
    @CallSuper
    open fun processEntry(entry: Entry) {
        extensionManager.setInstalling(entry.downloadId)
    }

    /**
     * Called before queue continues. Override this to handle when the removed entry is
     * currently being processed.
     *
     * @return true if this entry can be removed from queue.
     */
    open fun cancelEntry(entry: Entry): Boolean {
        return true
    }

    /**
     * Tells the queue to continue processing the next entry and updates the install step
     * of the completed entry ([waitingInstall]) to [ExtensionManager].
     *
     * @param resultStep new install step for the processed entry.
     * @see waitingInstall
     */
    fun continueQueue(resultStep: InstallStep) {
        val completedEntry = waitingInstall.exchange(null)
        if (completedEntry != null) {
            stallHandler.removeCallbacks(giveUp)
            extensionManager.updateInstallStep(completedEntry.downloadId, resultStep)
            checkQueue()
        }
    }

    /**
     * Checks the queue. The provided service will be stopped if the queue is empty.
     * Will not be run when not ready.
     *
     * @see ready
     */
    fun checkQueue() {
        if (!ready) {
            return
        }
        if (queue.isEmpty()) {
            service.stopSelf()
            return
        }
        val nextEntry = queue.first()
        if (waitingInstall.compareAndSet(null, nextEntry)) {
            queue.remove(nextEntry) // RK: remove by entry, not index (queue is now a set)
            // RK: an entry that hands off to the system's confirm dialog only finishes when that
            //     dialog answers. Left unanswered nothing ever calls continueQueue, so the service
            //     holds its foreground slot until Android takes the process down. Give up first.
            stallHandler.removeCallbacks(giveUp)
            stallHandler.postDelayed(giveUp, STALLED_INSTALL_TIMEOUT_MS)
            processEntry(nextEntry)
        }
    }

    /**
     * Call this method when the provided service is destroyed.
     */
    @CallSuper
    open fun onDestroy() {
        LocalBroadcastManager.getInstance(service).unregisterReceiver(cancelReceiver)
        stallHandler.removeCallbacks(giveUp)
        // RK: the one being installed counts too. Without this a give-up leaves its row reading
        //     "installing" with nothing left to finish it, until the app is restarted.
        waitingInstall.exchange(null)?.let { extensionManager.updateInstallStep(it.downloadId, InstallStep.Error) }
        queue.forEach { extensionManager.updateInstallStep(it.downloadId, InstallStep.Error) }
        queue.clear()
    }

    protected fun getActiveEntry(): Entry? = waitingInstall.load()

    /**
     * Cancels queue for the provided download ID if exists.
     *
     * @param downloadId Download ID as known by [ExtensionManager]
     */
    private fun cancelQueue(downloadId: Long) {
        val waitingInstall = this.waitingInstall.load()
        // RK: only cancel the in-progress install when ITS id matches; otherwise a cancel could kill
        // an unrelated waiting install (from Komikku 94eac94ce7).
        val toCancel = synchronized(queue) { queue.find { it.downloadId == downloadId } }
            ?: waitingInstall?.takeIf { it.downloadId == downloadId }
            ?: return
        if (cancelEntry(toCancel)) {
            queue.remove(toCancel)
            if (waitingInstall == toCancel) {
                // Currently processing removed entry, continue queue
                this.waitingInstall.store(null)
                checkQueue()
            }
            extensionManager.updateInstallStep(downloadId, InstallStep.Idle)
        }
    }

    /**
     * Install item to queue.
     *
     * @param downloadId Download ID as known by [ExtensionManager]
     * @param uri Uri of APK to install
     */
    data class Entry(val downloadId: Long, val uri: Uri)

    init {
        val filter = IntentFilter(ACTION_CANCEL_QUEUE)
        LocalBroadcastManager.getInstance(service).registerReceiver(cancelReceiver, filter)
    }

    companion object {
        private const val ACTION_CANCEL_QUEUE = "Installer.action.CANCEL_QUEUE"
        private const val EXTRA_DOWNLOAD_ID = "Installer.extra.DOWNLOAD_ID"

        /**
         * How long an install may sit unanswered before the queue is given up. Long enough that
         * someone who walks away mid-confirm still lands their tap, short enough that a stall does
         * not spend the day's foreground allowance, which the library and download jobs draw on too.
         */
        private const val STALLED_INSTALL_TIMEOUT_MS = 30L * 60 * 1000

        /**
         * Attempts to cancel the installation entry for the provided download ID.
         *
         * @param downloadId Download ID as known by [ExtensionManager]
         */
        fun cancelInstallQueue(context: Context, downloadId: Long) {
            val intent = Intent(ACTION_CANCEL_QUEUE)
            intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }
}
