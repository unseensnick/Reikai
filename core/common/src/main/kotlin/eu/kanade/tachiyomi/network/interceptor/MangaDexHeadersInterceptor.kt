package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Sends a MangaDex request only headers MangaDex accepts. A browser User-Agent is redirected to its bot
 * challenge on every `mangadex.org` host (MD@Home nodes 404 it), so a missing or browser one becomes the
 * app's; another site's Referer gets a cover's "read this at MangaDex" placeholder, so it is dropped. Komikku
 * fills only a missing User-Agent; Reikai rewrites both, because a cover request carries its source's
 * headers and a MangaDex cover filled in from MDList sits on any source's series.
 */
class MangaDexHeadersInterceptor(
    private val userAgent: String = defaultUserAgent(),
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isMangaDexHost(request.url.host)) return chain.proceed(request)
        val builder = request.newBuilder()
        val sentAgent = request.header("User-Agent")
        if (sentAgent.isNullOrEmpty() || sentAgent.startsWith("Mozilla/")) builder.header("User-Agent", userAgent)
        val refererHost = request.header("Referer")?.toHttpUrlOrNull()?.host
        if (refererHost != null && !isMangaDexHost(refererHost)) builder.removeHeader("Referer")
        return chain.proceed(builder.build())
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
    addInterceptor(MangaDexHeadersInterceptor()).addInterceptor(UserAgentInterceptor(defaultUserAgentProvider))
