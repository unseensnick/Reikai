package reikai.domain.track

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.presentation.track.trackerErrorMessage
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat

/**
 * A removal whose local half only makes sense once a remote one went through: a tracker binding and its
 * service entry, a library gallery and its account favourite. The remote goes first and a failure there
 * is toasted with the local state kept, so the user can retry. Both run on this app-wide scope, since a
 * screen closed mid-request would otherwise cancel the local half after the remote one landed. Upstream
 * drops the tracker binding either way; see novel-tracking.md and exh-subsystem.md.
 */
@SingleIn(AppScope::class)
class RemoteFirstRemoval internal constructor(private val context: Context, private val scope: CoroutineScope) {

    @Inject
    constructor(context: Context) : this(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** [name] is who the remote call went to, for the failure toast. */
    fun launch(name: String, alsoRemote: Boolean, remote: suspend () -> Unit, local: suspend () -> Unit) {
        scope.launch {
            try {
                if (alsoRemote) remote()
                local()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to remove from $name" }
                withUIContext { context.toast(context.trackerErrorMessage(name, e)) }
            }
        }
    }
}
