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
        val token = token ?: throw IOException("Not authenticated with RanobeDB")

        val authRequest = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .header("User-Agent", "Reikai v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(token: String?) {
        this.token = token
    }
}
