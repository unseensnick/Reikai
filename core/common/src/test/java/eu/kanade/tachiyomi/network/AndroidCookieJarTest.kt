package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AndroidCookieJarTest {

    private val url = "https://example.com/".toHttpUrl()
    private lateinit var manager: CookieManager

    @BeforeEach
    fun setUp() {
        // AndroidCookieJar grabs the singleton in its field initializer, so the static mock
        // has to be in place before the jar is constructed.
        mockkStatic(CookieManager::class)
        manager = mockk(relaxed = true)
        every { CookieManager.getInstance() } returns manager
    }

    @AfterEach
    fun tearDown() {
        io.mockk.unmockkStatic(CookieManager::class)
    }

    @Test
    fun `targeted removal matches a non-first cookie despite the leading space`() {
        // The split on ";" leaves " b=2" with a leading space; without trimming, the name " b"
        // never matched the "b" filter and the cookie was silently kept.
        every { manager.getCookie(any()) } returns "a=1; b=2"
        val expired = slot<String>()
        every { manager.setCookie(any(), capture(expired)) } returns Unit

        AndroidCookieJar().remove(url, cookieNames = listOf("b"))

        expired.captured shouldBe "b=;Max-Age=-1"
    }

    @Test
    fun `targeted removal expires only the named cookie`() {
        every { manager.getCookie(any()) } returns "a=1; b=2"

        val cleared = AndroidCookieJar().remove(url, cookieNames = listOf("b"))

        cleared shouldBe 1
    }

    @Test
    fun `targeted removal leaves an unnamed first cookie untouched`() {
        every { manager.getCookie(any()) } returns "a=1; b=2"

        AndroidCookieJar().remove(url, cookieNames = listOf("b"))

        verify(exactly = 0) { manager.setCookie(any(), "a=;Max-Age=-1") }
    }

    @Test
    fun `removal without a name filter expires every cookie`() {
        every { manager.getCookie(any()) } returns "a=1; b=2"

        val cleared = AndroidCookieJar().remove(url)

        cleared shouldBe 2
    }
}
