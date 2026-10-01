package exh.md.network

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import eu.kanade.tachiyomi.network.parseAs
import exh.md.utils.MdUtil
import exh.util.nullIfBlank
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import reikai.data.track.TrackerSignedOutException
import java.io.IOException

class MangaDexAuthInterceptor(
    private val trackPreferences: TrackPreferences,
    private val mdList: MdList,
) : Interceptor {

    @Volatile
    var token = trackPreferences.trackToken(mdList).get().nullIfBlank()

    @Volatile
    private var oauth: MALOAuth? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        if (token.isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }
        val loaded = oauth
            ?: MdUtil.loadOAuth(trackPreferences, mdList)?.also { oauth = it }
            ?: throw IOException("No authentication token")
        val current = if (loaded.isExpired()) refreshToken(chain, loaded) else loaded

        val response = chain.proceed(originalRequest.withBearer(current))
        val tokenIsExpired = response.headers["www-authenticate"]
            ?.contains("The access token expired") ?: false

        // Retry the request once with a new token in case it was not already refreshed
        // by the is expired check before.
        if (response.code == 401 && tokenIsExpired) {
            response.close()
            return chain.proceed(originalRequest.withBearer(refreshToken(chain, current)))
        }

        return response
    }

    /**
     * Called when the user authenticates with MangaDex for the first time. Sets the refresh token
     * and the oauth object.
     */
    fun setAuth(oauth: MALOAuth?) {
        token = oauth?.accessToken
        this.oauth = oauth
        MdUtil.saveOAuth(trackPreferences, mdList, oauth)
    }

    private fun Request.withBearer(oauth: MALOAuth) = newBuilder()
        .addHeader("Authorization", "Bearer ${oauth.accessToken}")
        .build()

    // One refresh at a time, and a thread that waited takes the token the first one fetched. Only
    // MangaDex rejecting the refresh token (400 or 401) signs the user out; any other failure is an
    // IOException that keeps the saved login for the next try.
    private fun refreshToken(chain: Interceptor.Chain, stale: MALOAuth): MALOAuth = synchronized(this) {
        val latest = oauth ?: throw TrackerSignedOutException(mdList.name)
        if (latest.accessToken != stale.accessToken) return@synchronized latest

        val response = chain.proceed(MdUtil.refreshTokenRequest(latest))
        if (response.code == 400 || response.code == 401) {
            response.close()
            setAuth(null)
            throw TrackerSignedOutException(mdList.name)
        }
        if (!response.isSuccessful) {
            response.close()
            throw IOException("MDList: failed to refresh account token (HTTP ${response.code})")
        }
        val refreshed = try {
            with(MdUtil.jsonParser) { response.parseAs<MALOAuth>() }
        } catch (e: Exception) {
            throw IOException("MDList: unreadable token refresh", e)
        }
        setAuth(refreshed)
        refreshed
    }
}
