package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** An address with the credentials that were embedded in it, once they have been taken back out. */
data class FlareSolverrAddress(
    val url: String,
    val username: String,
    val password: String,
) {
    // Redacted on purpose: this holds a live password and would otherwise reach a log or a crash report.
    override fun toString(): String = "FlareSolverrAddress(url=$url, username=$username, password=***)"
}

/**
 * The `Authorization` value for a reverse proxy in front of the bypass server, or null when neither a
 * username nor a password is configured. A password alone goes with an empty username, as curl and
 * browsers send `http://:password@host`.
 *
 * UTF-8 over OkHttp's ISO-8859-1 default is deliberate: it matches what a proxy's web UI stores, so
 * only a hand-built ISO-8859-1 htpasswd rejects a non-ASCII password (the docs say so).
 */
fun flareSolverrAuthHeader(username: String, password: String): String? {
    if (username.isBlank() && password.isEmpty()) return null
    return Credentials.basic(username.trim(), password, Charsets.UTF_8)
}

/** A proxy login held back because it would cross the internet unencrypted. */
class FlareSolverrLoginRefusedException : IOException("The sign-in needs https or an address on your network")

/**
 * The login header for a request to [url], or null when none is set or [url] is not on the saved
 * [serverUrl]'s scheme, host and port, so the login can reach no one but the server it was saved for.
 * Refused rather than left off where [isPrivateChannel] says no: an unauthenticated request would only
 * fail with a reason that hides the real one.
 */
fun flareSolverrLoginFor(url: String, serverUrl: String, username: String, password: String): String? {
    val header = flareSolverrAuthHeader(username, password) ?: return null
    if (!isSameServer(url, serverUrl)) return null
    if (!isPrivateChannel(url)) throw FlareSolverrLoginRefusedException()
    return header
}

/**
 * Pull `user:password@` back out of [rawUrl], or null when it carries none.
 *
 * OkHttp parses userinfo into the URL and then never derives a header from it, so credentials
 * typed into the address field are silently dropped. They also travel into every preference backup,
 * because the address key has no private prefix. The settings field refuses an address this splits,
 * and the upgrade migration moves the credentials out of one already stored.
 */
fun splitFlareSolverrUserInfo(rawUrl: String): FlareSolverrAddress? {
    val url = rawUrl.trim().toHttpUrlOrNull() ?: return null
    if (url.username.isEmpty() && url.password.isEmpty()) return null
    val stripped = url.newBuilder().username("").password("").build()
        .toString()
        .trimEnd('/')
    return FlareSolverrAddress(url = stripped, username = url.username, password = url.password)
}

/** The address key. */
const val FLARESOLVERR_URL_KEY = "flaresolverr_url"

private fun isSameServer(a: String, b: String): Boolean {
    val first = a.trim().toHttpUrlOrNull() ?: return false
    val second = b.trim().toHttpUrlOrNull() ?: return false
    return isSameOrigin(first, second)
}
