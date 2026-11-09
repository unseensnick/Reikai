package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * The cookies [request] goes out with on a client whose jar is [jar], by OkHttp's own rule: the jar's
 * for the url, or when the jar has none, the request's own Cookie header. The bypass server forwards
 * this set, so a request carries there what it would carry sent directly.
 */
fun cookiesSent(jar: CookieJar, request: Request): List<Cookie> =
    jar.loadForRequest(request.url).ifEmpty { headerCookies(request.header("Cookie"), request.url) }

/**
 * An LN plugin's jar: [jar]'s cookies plus the ones in the plugin's own Cookie [header], the header's
 * winning by name. OkHttp alone replaces the header with the jar's whenever the jar holds any, which
 * loses a plugin's age gate or read token.
 */
class WithPluginCookies(private val jar: CookieJar, private val header: String) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val own = headerCookies(header, url)
        val names = own.mapTo(HashSet()) { it.name }
        return jar.loadForRequest(url).filterNot { it.name in names } + own
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = jar.saveFromResponse(url, cookies)
}

private fun headerCookies(header: String?, url: HttpUrl): List<Cookie> =
    header.orEmpty().split(';').mapNotNull { part ->
        val name = part.substringBefore('=').trim()
        if (name.isEmpty() || '=' !in part) return@mapNotNull null
        runCatching { Cookie.Builder().name(name).value(part.substringAfter('=').trim()).domain(url.host).build() }
            .getOrNull()
    }
