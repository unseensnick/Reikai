package eu.kanade.tachiyomi.data.track.bangumi

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMOAuth
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import reikai.data.track.TrackerSignedOutException

class BangumiInterceptor(
    private val bangumi: Bangumi,
    private val json: Json,
) : Interceptor {

    /**
     * OAuth object used for authenticated requests.
     */
    @Volatile
    private var oauth: BGMOAuth? = bangumi.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // RK --> a signed-out call fails as an IOException, see TrackerSignedOutException
        var currAuth: BGMOAuth = oauth ?: throw TrackerSignedOutException("Bangumi")
        // RK <--

        if (currAuth.isExpired()) {
            currAuth = refreshToken(chain, currAuth)
        }

        return originalRequest.newBuilder()
            .header(
                "User-Agent",
                // RK: Reikai's own user agent
                "unseensnick/Reikai/v${BuildConfig.VERSION_NAME} (Android) (https://github.com/unseensnick/Reikai)",
            )
            .addHeader("Authorization", "Bearer ${currAuth.accessToken}")
            .build()
            .let(chain::proceed)
    }

    private fun refreshToken(chain: Interceptor.Chain, expired: BGMOAuth): BGMOAuth = synchronized(this) {
        val current = oauth ?: expired
        if (!current.isExpired()) return@synchronized current

        val refreshToken = current.refreshToken ?: throw TrackerSignedOutException("Bangumi") // RK
        val response = chain.proceed(BangumiApi.refreshTokenRequest(refreshToken))
        if (!response.isSuccessful) {
            response.close()
            return@synchronized current
        }
        with(json) { response.parseAs<BGMOAuth>() }.also(::newAuth)
    }

    fun newAuth(oauth: BGMOAuth?) {
        this.oauth = oauth
        bangumi.saveToken(oauth)
    }
}
