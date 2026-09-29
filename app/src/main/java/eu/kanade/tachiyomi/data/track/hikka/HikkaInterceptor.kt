package eu.kanade.tachiyomi.data.track.hikka

import eu.kanade.tachiyomi.data.track.hikka.dto.HKAuthTokenInfo
import eu.kanade.tachiyomi.data.track.hikka.dto.HKOAuth
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import reikai.data.track.TrackerSignedOutException
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class HikkaInterceptor(private val hikka: Hikka) : Interceptor {
    private val json: Json by injectLazy()
    private var oauth: HKOAuth? = hikka.loadOAuth()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // RK --> every throw here is an IOException, see TrackerSignedOutException
        val currAuth = oauth ?: throw TrackerSignedOutException("Hikka")

        if (currAuth.isExpired()) {
            val refreshTokenResponse = chain.proceed(HikkaApi.refreshTokenRequest(currAuth.accessToken))
            if (!refreshTokenResponse.isSuccessful) {
                refreshTokenResponse.close()
                hikka.logout()
                throw TrackerSignedOutException("Hikka")
            } else {
                refreshTokenResponse.close()
            }

            val authTokenInfoResponse = chain.proceed(HikkaApi.authTokenInfo(currAuth.accessToken))
            if (!authTokenInfoResponse.isSuccessful) {
                authTokenInfoResponse.close()
                throw IOException("Hikka: Auth token info failed")
            }
            // RK <--

            val authTokenInfo = with(json) {
                authTokenInfoResponse.parseAs<HKAuthTokenInfo>()
            }
            setAuth(HKOAuth(currAuth.accessToken, authTokenInfo.expiration, authTokenInfo.created))
        }

        val authRequest = originalRequest.newBuilder()
            .addHeader("auth", currAuth.accessToken)
            .addHeader("accept", "application/json")
            .build()

        return chain.proceed(authRequest)
    }

    fun setAuth(oauth: HKOAuth?) {
        this.oauth = oauth
        hikka.saveOAuth(oauth)
    }
}
