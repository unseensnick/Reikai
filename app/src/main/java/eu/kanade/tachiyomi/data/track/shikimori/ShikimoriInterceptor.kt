package eu.kanade.tachiyomi.data.track.shikimori

import eu.kanade.tachiyomi.data.track.shikimori.dto.SMOAuth
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import reikai.data.track.REIKAI_TRACKER_USER_AGENT
import reikai.data.track.TrackerSignedOutException

class ShikimoriInterceptor(
    private val shikimori: Shikimori,
    private val json: Json,
) : Interceptor {

    /**
     * OAuth object used for authenticated requests.
     */
    @Volatile
    private var oauth: SMOAuth? = shikimori.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // RK --> a signed-out call fails as an IOException, see TrackerSignedOutException
        var currAuth = oauth ?: throw TrackerSignedOutException("Shikimori")

        // Refresh access token if expired, once for all the requests that found it expired.
        if (currAuth.isExpired()) {
            currAuth = synchronized(this) {
                val latest = oauth ?: throw TrackerSignedOutException("Shikimori")
                if (!latest.isExpired()) return@synchronized latest

                val refreshToken = latest.refreshToken ?: throw TrackerSignedOutException("Shikimori")
                val response = chain.proceed(ShikimoriApi.refreshTokenRequest(refreshToken))
                // RK <--
                if (response.isSuccessful) {
                    with(json) { response.parseAs<SMOAuth>() }.also(::newAuth)
                } else {
                    response.close()
                    latest
                }
            }
        }
        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer ${currAuth.accessToken}")
            // RK: Reikai's own user agent, shared with the Shikimori recommendations
            .header("User-Agent", REIKAI_TRACKER_USER_AGENT)
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(oauth: SMOAuth?) {
        this.oauth = oauth
        shikimori.saveToken(oauth)
    }
}
