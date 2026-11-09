package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Sends every request to a host FlareSolverr has solved with the User-Agent its clearance is bound
 * to, whatever User-Agent the request carries, for as long as [pinnedUserAgentFor] answers one (only
 * while FlareSolverr is in use, and for a request it would carry). A network interceptor, because a
 * request retried from inside [CloudflareInterceptor] never passes the application interceptors ahead
 * of it again.
 */
fun OkHttpClient.Builder.pinFlareSolverrUserAgents(pinnedUserAgentFor: (Request) -> String?) =
    addNetworkInterceptor(
        Interceptor { chain ->
            val request = chain.request()
            val pinned = pinnedUserAgentFor(request)
            chain.proceed(if (pinned == null) request else request.newBuilder().header("User-Agent", pinned).build())
        },
    )
