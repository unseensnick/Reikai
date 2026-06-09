package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class UserAgentInterceptor(
    private val defaultUserAgentProvider: () -> String,
    // RK -->
    private val pinnedUserAgentFor: (String) -> String? = { null },
    // RK <--
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // RK -->
        // A host FlareSolverr has solved must keep using the UA the cf_clearance cookie is bound
        // to, so pin it here even when the request already carries a User-Agent.
        pinnedUserAgentFor(originalRequest.url.host)?.let { pinnedUa ->
            val pinnedRequest = originalRequest
                .newBuilder()
                .removeHeader("User-Agent")
                .addHeader("User-Agent", pinnedUa)
                .build()
            return chain.proceed(pinnedRequest)
        }
        // RK <--

        return if (originalRequest.header("User-Agent").isNullOrEmpty()) {
            val newRequest = originalRequest
                .newBuilder()
                .removeHeader("User-Agent")
                .addHeader("User-Agent", defaultUserAgentProvider())
                .build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(originalRequest)
        }
    }
}
