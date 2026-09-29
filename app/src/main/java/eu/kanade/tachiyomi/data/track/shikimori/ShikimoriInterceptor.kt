package eu.kanade.tachiyomi.data.track.shikimori

import eu.kanade.tachiyomi.data.track.shikimori.dto.SMOAuth
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import reikai.data.track.REIKAI_TRACKER_USER_AGENT
import reikai.data.track.TrackerSignedOutException
import uy.kohesive.injekt.injectLazy

class ShikimoriInterceptor(private val shikimori: Shikimori) : Interceptor {

    private val json: Json by injectLazy()

    /**
     * OAuth object used for authenticated requests.
     */
    private var oauth: SMOAuth? = shikimori.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // RK --> a signed-out call fails as an IOException, see TrackerSignedOutException
        var currAuth = oauth ?: throw TrackerSignedOutException("Shikimori")

        // Refresh access token if expired.
        if (currAuth.isExpired()) {
            val refreshToken = currAuth.refreshToken ?: throw TrackerSignedOutException("Shikimori")
            val response = chain.proceed(ShikimoriApi.refreshTokenRequest(refreshToken))
            // RK <--
            if (response.isSuccessful) {
                currAuth = with(json) {
                    response.parseAs<SMOAuth>()
                }
                newAuth(currAuth)
            } else {
                response.close()
            }
        }
        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer ${oauth!!.accessToken}")
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
