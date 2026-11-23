package reikai.data.track

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.util.PkceCodes
import eu.kanade.tachiyomi.util.PkceUtil
import logcat.LogPriority
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat
import java.security.SecureRandom
import java.util.Base64

// A browser login's PKCE verifier and OAuth state live in preferences rather than memory, because Android
// may kill Reikai while the user signs in and the redirect then lands in a new process.

/** The PKCE codes of a new login URL, its verifier stored for the code exchange, replacing any earlier one. */
fun TrackPreferences.newLoginPkce(tracker: Tracker): PkceCodes =
    PkceUtil.generateS256Codes().also { trackPkceCodeVerifier(tracker).set(it.codeVerifier) }

/** A new OAuth state for a login URL, stored for [isLoginState]. */
fun TrackPreferences.newLoginState(tracker: Tracker): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        .also { trackOAuthState(tracker).set(it) }
}

/** Whether [state] is the one the latest login URL carried. With no login pending, nothing matches. */
fun TrackPreferences.isLoginState(tracker: Tracker, state: String): Boolean {
    val stored = trackOAuthState(tracker).get()
    return stored.isNotEmpty() && state == stored
}

/**
 * Runs [exchange] with the stored verifier. Null when no login is pending: any other verifier would fail
 * the server's check, so nothing is sent and a session the user has is left alone. A completed exchange
 * clears the pending login whatever its outcome, since a verifier is good for one exchange. A cancelled
 * one rethrows and keeps it, for the redirect a recreated login activity delivers again.
 */
suspend fun <T> TrackPreferences.exchangeLoginCode(
    tracker: Tracker,
    exchange: suspend (codeVerifier: String) -> T,
): Result<T>? {
    val codeVerifier = trackPkceCodeVerifier(tracker).get()
    if (codeVerifier.isEmpty()) {
        logcat(LogPriority.ERROR) { "${tracker.name} login failed: no PKCE code verifier stored for it" }
        return null
    }
    val result = runCatchingCancellable { exchange(codeVerifier) }
    trackPkceCodeVerifier(tracker).delete()
    trackOAuthState(tracker).delete()
    return result
}
