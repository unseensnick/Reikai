package eu.kanade.tachiyomi.data.track.mangaupdates

import eu.kanade.tachiyomi.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import reikai.data.track.TrackerSignedOutException

class MangaUpdatesInterceptor(
    mangaUpdates: MangaUpdates,
) : Interceptor {

    private var token: String? = mangaUpdates.restoreSession()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val token = token ?: throw TrackerSignedOutException("MangaUpdates") // RK: read as signed out

        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer $token")
            // RK: the app's own name in the User-Agent
            .header("User-Agent", "Reikai v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(token: String?) {
        this.token = token
    }
}
