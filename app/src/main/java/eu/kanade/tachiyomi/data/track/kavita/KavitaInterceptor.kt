package eu.kanade.tachiyomi.data.track.kavita

import okhttp3.Interceptor
import okhttp3.Response
import reikai.data.track.REIKAI_TRACKER_USER_AGENT

class KavitaInterceptor(private val kavita: Kavita) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (kavita.authentications == null) {
            kavita.loadOAuth()
        }
        val jwtToken = kavita.authentications?.getToken(
            kavita.api.getApiFromUrl(originalRequest.url.toString()),
        )

        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer $jwtToken")
            .header("User-Agent", REIKAI_TRACKER_USER_AGENT) // RK: identity
            .build()

        return chain.proceed(authRequest)
    }
}
