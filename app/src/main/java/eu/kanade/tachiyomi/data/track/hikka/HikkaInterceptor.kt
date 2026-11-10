package eu.kanade.tachiyomi.data.track.hikka

import eu.kanade.tachiyomi.data.track.hikka.dto.HKAuthTokenInfo
import eu.kanade.tachiyomi.data.track.hikka.dto.HKOAuth
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import reikai.data.track.TrackerSignedOutException
import java.io.IOException

class HikkaInterceptor(
    private val hikka: Hikka,
    private val json: Json,
) : Interceptor {

    @Volatile
    private var oauth: HKOAuth? = hikka.loadOAuth()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        var currAuth = oauth ?: throw TrackerSignedOutException("Hikka") // RK: see refreshToken

        if (currAuth.isExpired()) {
            currAuth = refreshToken(chain, currAuth)
        }

        val authRequest = originalRequest.newBuilder()
            .addHeader("auth", currAuth.accessToken)
            .addHeader("accept", "application/json")
            .build()

        return chain.proceed(authRequest)
    }

    private fun refreshToken(chain: Interceptor.Chain, expired: HKOAuth): HKOAuth = synchronized(this) {
        val currAuth = oauth ?: expired
        if (!currAuth.isExpired()) return@synchronized currAuth

        // RK --> every throw here is an IOException, see TrackerSignedOutException
        val refreshTokenResponse = chain.proceed(HikkaApi.refreshTokenRequest(currAuth.accessToken))
        if (!refreshTokenResponse.isSuccessful) {
            refreshTokenResponse.close()
            hikka.logout()
            throw TrackerSignedOutException("Hikka")
        }
        refreshTokenResponse.close()

        val authTokenInfoResponse = chain.proceed(HikkaApi.authTokenInfo(currAuth.accessToken))
        if (!authTokenInfoResponse.isSuccessful) {
            authTokenInfoResponse.close()
            throw IOException("Hikka: Auth token info failed")
        }
        // RK <--

        val authTokenInfo = with(json) {
            authTokenInfoResponse.parseAs<HKAuthTokenInfo>()
        }
        HKOAuth(currAuth.accessToken, authTokenInfo.expiration, authTokenInfo.created).also(::setAuth)
    }

    fun setAuth(oauth: HKOAuth?) {
        this.oauth = oauth
        hikka.saveOAuth(oauth)
    }
}
