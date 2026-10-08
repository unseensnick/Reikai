package eu.kanade.tachiyomi.data.track.anilist

import eu.kanade.tachiyomi.data.track.anilist.dto.ALOAuth
import okhttp3.Interceptor
import okhttp3.Response
import reikai.data.track.REIKAI_TRACKER_USER_AGENT
import reikai.data.track.TrackerSignedOutException

class AnilistInterceptor(val anilist: Anilist, private var token: String?) : Interceptor {

    /**
     * OAuth object used for authenticated requests.
     */
    private var oauth: ALOAuth? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // RK --> every missing or dead login reads as signed out, see TrackerSignedOutException
        if (token.isNullOrEmpty()) {
            throw TrackerSignedOutException("Anilist")
        }
        if (oauth == null) {
            oauth = anilist.loadOAuth() ?: throw TrackerSignedOutException("Anilist")
        }
        if (oauth!!.isExpired()) {
            anilist.logout()
            throw TrackerSignedOutException("Anilist")
        }
        // RK <--

        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer ${oauth!!.accessToken}")
            // RK: the app's own name in the User-Agent
            .header("User-Agent", REIKAI_TRACKER_USER_AGENT)
            .build()

        return chain.proceed(authRequest)
    }

    /**
     * Called when the user authenticates with Anilist for the first time. Sets the refresh token
     * and the oauth object.
     */
    fun setAuth(oauth: ALOAuth?) {
        token = oauth?.accessToken
        this.oauth = oauth
        anilist.saveOAuth(oauth)
    }
}
