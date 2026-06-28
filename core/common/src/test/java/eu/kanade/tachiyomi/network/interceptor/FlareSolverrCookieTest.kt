package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * FlareSolverr returns cookies whose domain may be blank, bare, or already dotted. They're handed to
 * Android's CookieManager, which only treats a cookie as a domain cookie (matching the apex and all
 * subdomains) when the domain has a leading dot. These pin that normalization.
 */
class FlareSolverrCookieTest {

    private fun cookie(domain: String) = FlareSolverrCookie(name = "n", value = "v", domain = domain)

    @Test
    fun `a blank domain falls back to the request host with a leading dot`() {
        cookie(domain = "").toRawCookieString("example.com") shouldBe "n=v; Domain=.example.com; Path=/"
    }

    @Test
    fun `an already-dotted domain is left as-is`() {
        cookie(domain = ".foo.com").toRawCookieString("example.com") shouldBe "n=v; Domain=.foo.com; Path=/"
    }

    @Test
    fun `a bare domain gains a leading dot`() {
        cookie(domain = "foo.com").toRawCookieString("example.com") shouldBe "n=v; Domain=.foo.com; Path=/"
    }
}
