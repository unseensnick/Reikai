package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A tracker signed into by driving its own website in a WebView and reading the session cookie it
 * sets, for services offering neither an OAuth flow nor a token to paste.
 *
 * Extraction lives on each tracker rather than in the login screen. tsundoku puts it in one `when`
 * over tracker ids, which grows with every service added; a capability keeps each service's cookie
 * knowledge with the service.
 */
interface CookieLoginTracker {

    /** Page the user lands on to sign in normally. */
    val cookieLoginUrl: String

    /** Origin whose cookies are read, in the form `CookieManager.getCookie` expects. */
    val cookieDomain: String

    /**
     * Reduce a raw `name=value; name=value` cookie string to the credential worth storing, or null
     * while the user is not signed in yet.
     *
     * Never log the argument or the result: both are a live session for someone's account.
     */
    fun credentialFromCookies(cookies: String): String?

    /** Persist [credential], and whatever else the service needs, such as a display name. */
    suspend fun loginWithCookie(credential: String)

    /**
     * Signs the in-app browser out of the site too; [BaseTracker.logout] runs it for every cookie-login
     * tracker. Otherwise the next WebView sign-in silently captures the old account's session again.
     */
    fun clearSiteCookies(cookieJar: AndroidCookieJar) {
        cookieJar.remove(cookieDomain.toHttpUrl())
    }
}
