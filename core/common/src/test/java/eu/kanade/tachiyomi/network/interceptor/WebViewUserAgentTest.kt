package eu.kanade.tachiyomi.network.interceptor

import android.webkit.WebView
import eu.kanade.tachiyomi.util.system.setUserAgent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [useRequestUserAgent], the User-Agent rule every WebView the fetch opens follows, a reused page included. */
class WebViewUserAgentTest {

    private val webView = mockk<WebView>(relaxed = true) { every { settings.userAgentString } returns "app" }

    @BeforeEach
    fun setUp() {
        // setUserAgent reads the WebView's features, which a JVM test has none of.
        mockkStatic("eu.kanade.tachiyomi.util.system.WebViewUtilKt")
        every { any<WebView>().setUserAgent(any()) } returns Unit
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a request's own User-Agent is the one the page sends`() {
        webView.useRequestUserAgent("plugin")

        verify { webView.setUserAgent("plugin") }
    }

    @Test
    fun `a blank User-Agent keeps the WebView's own`() {
        webView.useRequestUserAgent(" ")

        verify(exactly = 0) { webView.setUserAgent(any()) }
    }
}
