package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Sends every request to a host FlareSolverr has solved with the User-Agent its clearance is bound
 * to, whatever User-Agent the request carries. A network interceptor, because a request retried
 * from inside [CloudflareInterceptor] never passes the application interceptors ahead of it again.
 */
fun OkHttpClient.Builder.pinFlareSolverrUserAgents(pinnedUserAgentFor: (String) -> String?) =
    addNetworkInterceptor(
        Interceptor { chain ->
            val request = chain.request()
            val pinned = pinnedUserAgentFor(request.url.host)
            chain.proceed(if (pinned == null) request else request.newBuilder().header("User-Agent", pinned).build())
        },
    )
