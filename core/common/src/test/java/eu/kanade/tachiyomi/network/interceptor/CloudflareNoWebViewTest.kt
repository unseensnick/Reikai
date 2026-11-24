package eu.kanade.tachiyomi.network.interceptor

import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.system.WebViewUtil
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/** The bypass server needs no WebView, so it is the one solve a device without one can still use. */
class CloudflareNoWebViewTest {

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a challenge on a device with no WebView is answered by the bypass server`() {
        // Android's main thread, which the WebView interceptor reaches for as it is built.
        mockkStatic(ContextCompat::class)
        every { ContextCompat.getMainExecutor(any()) } returns Executor { it.run() }
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk()
        mockkConstructor(Handler::class)
        every { anyConstructed<Handler>().post(any()) } returns true
        mockkObject(WebViewUtil)
        every { WebViewUtil.supportsWebView(any()) } returns false
        val request = Request.Builder().url("https://site.test/page").build()
        val flareSolverr = mockk<FlareSolverrClient>(relaxed = true) {
            every { handles(any()) } returns true
            every { shouldSkipWebView(any()) } returns false
            every { resolve(any(), any()) } answers { response(firstArg(), 200) }
        }
        val interceptor = CloudflareInterceptor(
            mockk(relaxed = true),
            mockk<AndroidCookieJar>(relaxed = true),
            // The "WebView required" toast is launched here, and never runs.
            CoroutineScope(Job()).apply { cancel() },
            { "ua" },
            mockk<NetworkPreferences>(relaxed = true),
            flareSolverr,
        )
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns request
            every { proceed(any()) } answers { response(firstArg(), 403, challenged = true) }
            every { cookieJar } returns CookieJar.NO_COOKIES
        }

        interceptor.intercept(chain).code shouldBe 200
    }

    private fun response(request: Request, code: Int, challenged: Boolean = false): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .apply { if (challenged) header("cf-mitigated", "challenge").header("Server", "cloudflare") }
        .body("".toResponseBody())
        .build()
}
