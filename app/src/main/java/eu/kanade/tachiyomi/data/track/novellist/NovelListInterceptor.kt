package eu.kanade.tachiyomi.data.track.novellist

import eu.kanade.tachiyomi.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * One credential shape, the JWT the sign-in cookie carries, sent as the `jwtAuth` bearer the
 * service's OpenAPI document declares. None of tsundoku's spoofed CORS headers are reproduced: every
 * write route answers 401 rather than 403 without them, so they reach the route unaided.
 */
class NovelListInterceptor(
    private var token: String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val credential = token ?: throw IOException("Not authenticated with NovelList")

        val authRequest = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $credential")
            .header("User-Agent", "Reikai v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(token: String?) {
        this.token = token
    }
}
