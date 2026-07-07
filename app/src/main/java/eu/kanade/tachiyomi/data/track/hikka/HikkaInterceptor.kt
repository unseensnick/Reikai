package eu.kanade.tachiyomi.data.track.hikka

import eu.kanade.tachiyomi.data.track.hikka.dto.HKAuthTokenInfo
import eu.kanade.tachiyomi.data.track.hikka.dto.HKOAuth
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class HikkaInterceptor(private val hikka: Hikka) : Interceptor {
    private val json: Json by injectLazy()
    private var oauth: HKOAuth? = hikka.loadOAuth()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val currAuth = oauth ?: throw Exception("Hikka: You are not authorized")

        if (currAuth.isExpired()) {
            // RK: close each refresh response. Upstream leaks the refresh-token response (never closed
            // on the success path) and then reads the token-info body after closing it, so the very
            // next call throws "cannot make a new request because the previous response is still open".
            val refreshSucceeded = chain.proceed(HikkaApi.refreshTokenRequest(currAuth.accessToken))
                .use { it.isSuccessful }
            if (!refreshSucceeded) {
                hikka.logout()
                throw Exception("Hikka: The token is expired")
            }

            val authTokenInfo = chain.proceed(HikkaApi.authTokenInfo(currAuth.accessToken))
                .use { json.decodeFromString<HKAuthTokenInfo>(it.body.string()) }
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
