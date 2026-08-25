package eu.kanade.tachiyomi.data.track.ranobedb

import eu.kanade.tachiyomi.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class RanobeDbInterceptor(
    ranobeDb: RanobeDb,
) : Interceptor {

    private var token: String? = ranobeDb.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        val credential = token ?: throw IOException("Not authenticated with RanobeDB")

        val authRequest = chain.request().newBuilder()
            // A WebView login stores the session cookie, a token login stores the token itself, and
            // the server accepts either on /api/v0/user. The prefix tells them apart; see
            // RanobeDb.SESSION_COOKIE_PREFIX for why it cannot collide with a token.
            .apply {
                if (credential.startsWith(RanobeDb.SESSION_COOKIE_PREFIX)) {
                    addHeader("Cookie", credential)
                } else {
                    addHeader("Authorization", "Bearer $credential")
                }
            }
            .header("User-Agent", "Reikai v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(token: String?) {
        this.token = token
    }
}
