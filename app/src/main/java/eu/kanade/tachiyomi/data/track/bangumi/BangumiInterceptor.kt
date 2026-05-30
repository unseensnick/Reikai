package eu.kanade.tachiyomi.data.track.bangumi

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMOAuth
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class BangumiInterceptor(val bangumi: Bangumi) : Interceptor {

    private val json: Json by injectLazy()

    /**
     * OAuth object used for authenticated requests.
     */
    private var oauth: BGMOAuth? = bangumi.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        var currAuth = oauth ?: throw Exception("Not authenticated with Bangumi")

        if (currAuth.isExpired()) {
            val response = chain.proceed(BangumiApi.refreshTokenRequest(currAuth.refreshToken!!))
            if (response.isSuccessful) {
                currAuth = json.decodeFromString<BGMOAuth>(response.body.string())
                newAuth(currAuth)
            } else {
                response.close()
            }
        }

        // v0 API takes the token as a Bearer header (the old v1 API used a query param / form field).
        val authRequest = originalRequest.newBuilder()
            .header("User-Agent", "null2264/yokai/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .addHeader("Authorization", "Bearer ${currAuth.accessToken}")
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(oauth: BGMOAuth?) {
        this.oauth = if (oauth == null) {
            null
        } else {
            BGMOAuth(
                oauth.accessToken,
                oauth.tokenType,
                System.currentTimeMillis() / 1000,
                oauth.expiresIn,
                oauth.refreshToken,
                this.oauth?.userId,
            )
        }

        bangumi.saveToken(oauth)
    }
}
