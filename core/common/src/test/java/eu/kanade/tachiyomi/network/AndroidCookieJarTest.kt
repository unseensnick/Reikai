package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
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

    /** Every cookie string handed to the manager, in order. */
    private fun captureExpiries(): List<String> {
        val expiries = mutableListOf<String>()
        val captured = slot<String>()
        every { manager.setCookie(any(), capture(captured)) } answers { expiries += captured.captured }
        return expiries
    }

    @Test
    fun `targeted removal matches a non-first cookie despite the leading space`() {
        // The split on ";" leaves " b=2" with a leading space; without trimming, the name " b"
        // never matched the "b" filter and the cookie was silently kept.
        every { manager.getCookie(any()) } returns "a=1; b=2"
        val expiries = captureExpiries()

        AndroidCookieJar().remove(url, cookieNames = listOf("b"))

        expiries.first() shouldBe "b=; Max-Age=-1; Path=/"
    }

    @Test
    fun `removal expires the cookie at the root path rather than the request's directory`() {
        // A bare "name=" inherits the request URL's directory as its path, and the store keys on
        // (name, domain, path), so an expiry written from a deep URL never matched a cookie set
        // at "/". Cloudflare sets cf_clearance at "/".
        every { manager.getCookie(any()) } returns "cf_clearance=1"
        val expiries = captureExpiries()

        AndroidCookieJar().remove("https://example.com/manga/123".toHttpUrl(), cookieNames = listOf("cf_clearance"))

        expiries.all { "; Path=/" in it } shouldBe true
    }

    @Test
    fun `removal expires the cookie on every parent domain it could be stored under`() {
        // Cloudflare sets cf_clearance on ".example.com", which a host-only expiry cannot touch.
        every { manager.getCookie(any()) } returns "cf_clearance=1"
        val expiries = captureExpiries()

        AndroidCookieJar().remove("https://sub.example.com/".toHttpUrl(), cookieNames = listOf("cf_clearance"))

        expiries shouldContainExactly listOf(
            "cf_clearance=; Max-Age=-1; Path=/",
            "cf_clearance=; Max-Age=-1; Path=/; Domain=.sub.example.com",
            "cf_clearance=; Max-Age=-1; Path=/; Domain=.example.com",
        )
    }

    @Test
    fun `removal never expires the public suffix itself`() {
        every { manager.getCookie(any()) } returns "cf_clearance=1"
        val expiries = captureExpiries()

        AndroidCookieJar().remove(url, cookieNames = listOf("cf_clearance"))

        expiries shouldContainExactly listOf(
            "cf_clearance=; Max-Age=-1; Path=/",
            "cf_clearance=; Max-Age=-1; Path=/; Domain=.example.com",
        )
    }

    @Test
    fun `removal on a single-label host writes only the host-only expiry`() {
        every { manager.getCookie(any()) } returns "cf_clearance=1"
        val expiries = captureExpiries()

        AndroidCookieJar().remove("http://localhost/".toHttpUrl(), cookieNames = listOf("cf_clearance"))

        expiries shouldContainExactly listOf("cf_clearance=; Max-Age=-1; Path=/")
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
        val expiries = captureExpiries()

        AndroidCookieJar().remove(url, cookieNames = listOf("b"))

        expiries.none { it.startsWith("a=") } shouldBe true
    }

    @Test
    fun `removal without a name filter expires every cookie`() {
        every { manager.getCookie(any()) } returns "a=1; b=2"

        val cleared = AndroidCookieJar().remove(url)

        cleared shouldBe 2
    }
}
