package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.NetworkPreferences
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
 * The `Authorization` value for a reverse proxy in front of the bypass server, or null when no
 * username is configured.
 *
 * UTF-8 is deliberate. OkHttp's default is ISO-8859-1, and the two encodings produce different
 * bytes for a non-ASCII password, so only one of them matches what the server stored. A proxy
 * configured through a web UI holds the UTF-8 bytes the browser submitted, which is the setup this
 * was built against; a hand-built ISO-8859-1 htpasswd will reject such a password, and the docs
 * say so. For an ASCII password both encodings are byte-identical and the choice cannot matter.
 */
fun flareSolverrAuthHeader(username: String, password: String): String? {
    if (username.isBlank()) return null
    return Credentials.basic(username, password, Charsets.UTF_8)
}

/**
 * Pull `user:password@` back out of [rawUrl], or null when it carries none.
 *
 * OkHttp parses userinfo into the URL and then never derives a header from it, so credentials
 * typed into the address field are silently dropped. They also travel into every preference backup,
 * because the address key has no private prefix. Both the upgrade migration and the backup restorer
 * run stored addresses through this, so neither entry point can leave a password in there.
 */
fun splitFlareSolverrUserInfo(rawUrl: String): FlareSolverrAddress? {
    val url = rawUrl.trim().toHttpUrlOrNull() ?: return null
    if (url.username.isEmpty() && url.password.isEmpty()) return null
    val stripped = url.newBuilder().username("").password("").build()
        .toString()
        .trimEnd('/')
    return FlareSolverrAddress(url = stripped, username = url.username, password = url.password)
}

/** The address key, shared with the upgrade migration and the backup restorer that clean it. */
const val FLARESOLVERR_URL_KEY = "flaresolverr_url"

/**
 * Store [rawUrl] with any embedded credentials moved into the username and password preferences.
 *
 * Called from both the upgrade migration and the backup restorer, because a fresh install marks
 * every migration done without running it, so a restored address would otherwise keep its password
 * where nothing reads it and every later backup copies it. A backup may be someone else's, so the
 * saved login never follows an address to another server, and an address the settings field would
 * refuse is not stored.
 */
fun NetworkPreferences.carryFlareSolverrUserInfo(rawUrl: String) {
    val split = splitFlareSolverrUserInfo(rawUrl)
    val address = split?.url ?: rawUrl
    if (address.isNotBlank() && address.trim().toHttpUrlOrNull() == null) return
    val current = flareSolverrUrl.get()
    if (address.isNotBlank() && current.isNotBlank() && !isSameServer(current, address)) {
        flareSolverrUsername.delete()
        flareSolverrPassword.delete()
    }
    flareSolverrUrl.set(address)
    // Credentials already configured by hand win as a pair: they are the ones the reader last verified.
    if (split != null && flareSolverrUsername.get().isBlank() && flareSolverrPassword.get().isBlank()) {
        flareSolverrUsername.set(split.username)
        flareSolverrPassword.set(split.password)
    }
}

private fun isSameServer(a: String, b: String): Boolean {
    val first = a.trim().toHttpUrlOrNull() ?: return false
    val second = b.trim().toHttpUrlOrNull() ?: return false
    return isSameOrigin(first, second)
}
