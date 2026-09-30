package eu.kanade.tachiyomi.data.track.ranobedb

import android.os.SystemClock
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.UnknownHostException

/**
 * The app's shared cookie jar holds whatever session the WebView last left on ranobedb.org, and
 * OkHttp would send it over the stored credential, so a call could run as another account.
 */
class RanobeDbApiCookieTest {

    @BeforeEach
    fun setUp() {
        // The rate limiter reads the uptime clock, which an Android stub cannot answer on the JVM.
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    @Test
    fun `an authenticated call never reads the shared cookie jar`() = runTest {
        val reached = mutableListOf<String>()
        val sharedJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                reached += "shared jar"
                return listOfNotNull(Cookie.parse(url, "rdb_session=another-account"))
            }
        }
        // The jar is read just before the host is looked up, so failing the lookup keeps the test
        // offline while proving the request got that far.
        val client = OkHttpClient.Builder()
            .cookieJar(sharedJar)
            .dns {
                reached += "host lookup"
                throw UnknownHostException(it)
            }
            .build()
        val ranobeDb = mockk<RanobeDb> { every { restoreToken() } returns "token-of-this-account" }

        runCatching { RanobeDbApi(RanobeDbInterceptor(ranobeDb), client).deleteSeriesListEntry(1L) }
        reached shouldBe listOf("host lookup")
    }
}
