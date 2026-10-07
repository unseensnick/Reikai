package eu.kanade.tachiyomi.network.interceptor

import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.AndroidCookieJar
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CloudflareClearanceTest {

    private lateinit var manager: CookieManager

    @BeforeEach
    fun setUp() {
        // AndroidCookieJar grabs the singleton in its field initializer.
        mockkStatic(CookieManager::class)
        manager = mockk(relaxed = true)
        every { CookieManager.getInstance() } returns manager
    }

    @AfterEach
    fun tearDown() = unmockkStatic(CookieManager::class)

    @Test
    fun `the clearance is the cf_clearance cookie among the site's others`() {
        every { manager.getCookie(any()) } returns "__cf_bm=a; cf_clearance=b; session=c"

        AndroidCookieJar().clearanceFor("https://example.com/".toHttpUrl())?.value shouldBe "b"
    }
}
