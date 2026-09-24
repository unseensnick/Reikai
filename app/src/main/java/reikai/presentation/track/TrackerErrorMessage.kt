package reikai.presentation.track

import android.content.Context
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.system.isOnline
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** What a failed tracker call tells the user, for both content types' binds and updates. */
sealed interface TrackerError {
    data object Offline : TrackerError
    data object Unreachable : TrackerError
    data object SignedOut : TrackerError
    data class Http(val code: Int) : TrackerError
    data class Other(val message: String?) : TrackerError

    companion object {
        // Named by the tracker rather than the host: a tracker's API host is often a backend the user
        // has never seen, and Mihon's source formatter sends HTTP errors to a WebView trackers lack.
        // The cause chain is walked because OkHttp's await wraps every network failure in a bare
        // IOException carrying the real one as its cause.
        fun of(error: Throwable, isOnline: Boolean): TrackerError =
            generateSequence(error) { it.cause }.firstNotNullOfOrNull { kindOf(it, isOnline) }
                ?: Other(error.message)

        private fun kindOf(error: Throwable, isOnline: Boolean): TrackerError? = when (error) {
            is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                if (isOnline) Unreachable else Offline
            is HttpException -> if (error.code == 401 || error.code == 403) SignedOut else Http(error.code)
            else -> null
        }
    }
}

fun Context.trackerErrorMessage(tracker: Tracker, error: Throwable): String =
    when (val kind = TrackerError.of(error, isOnline())) {
        TrackerError.Offline -> stringResource(MR.strings.exception_offline)
        TrackerError.Unreachable -> stringResource(MR.strings.exception_unknown_host, tracker.name)
        TrackerError.SignedOut -> stringResource(MR.strings.tracker_error_signed_out, tracker.name)
        is TrackerError.Http -> stringResource(MR.strings.tracker_error_http, tracker.name, kind.code)
        is TrackerError.Other -> kind.message ?: error::class.simpleName.orEmpty()
    }
