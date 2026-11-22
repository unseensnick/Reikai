package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Gives a MangaDex request the app's User-Agent when it has none or a browser one. MangaDex redirects a
 * browser User-Agent to its bot challenge on every `mangadex.org` host, and MD@Home nodes 404 it, so one is
 * never right here. Komikku fills only a missing one; Reikai also replaces a browser one, because a cover
 * request carries its source's headers and a MangaDex cover filled in from MDList sits on any source's series.
 */
class MangaDexUserAgentInterceptor(
    private val userAgent: String = defaultUserAgent(),
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val sent = request.header("User-Agent")
        if (!isMangaDexHost(request.url.host) || !(sent.isNullOrEmpty() || sent.startsWith("Mozilla/"))) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
    }

    companion object {
        /** The site, API, auth and covers (`mangadex.org`), and MD@Home image nodes (`mangadex.network`). */
        private val MANGADEX_HOSTS = listOf("mangadex.org", "mangadex.network")

        fun isMangaDexHost(host: String): Boolean = MANGADEX_HOSTS.any { host == it || host.endsWith(".$it") }

        /** What the MangaDex extension sends. */
        fun defaultUserAgent(): String {
            val httpAgent = System.getProperty("http.agent")?.takeIf { it.isNotBlank() } ?: "Dalvik/2.1.0"
            return "Tachiyomi $httpAgent"
        }
    }
}

/** The shared client's User-Agent interceptors, MangaDex's first, since [UserAgentInterceptor] keeps any set one. */
fun OkHttpClient.Builder.addUserAgentInterceptors(defaultUserAgentProvider: () -> String): OkHttpClient.Builder =
    addInterceptor(MangaDexUserAgentInterceptor()).addInterceptor(UserAgentInterceptor(defaultUserAgentProvider))
