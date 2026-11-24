package reikai.novel.network

import android.content.Context
import android.webkit.WebSettings
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NovelRequestHeadersTest {

    private val context = mockk<Context>()

    // The cache is process-wide, so each case starts and leaves it empty for the tests after it in the fork.
    @BeforeEach
    fun setUp() {
        clearCache()
        mockkStatic(WebSettings::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        clearCache()
    }

    private fun clearCache() = Class.forName("reikai.novel.network.NovelRequestHeadersKt")
        .getDeclaredField("cachedDeviceUserAgent")
        .apply { isAccessible = true }
        .set(null, null)

    @Test
    fun `a failed lookup of the device User-Agent is asked again next time`() {
        every { WebSettings.getDefaultUserAgent(context) } throws IllegalStateException("no WebView yet") andThen
            "Device UA"
        deviceWebViewUserAgent(context)

        deviceWebViewUserAgent(context) shouldBe "Device UA"
    }

    // Each novel picture and plugin fetch reads it, and a lookup loads the WebView provider.
    @Test
    fun `a device User-Agent once found is not asked for again`() {
        every { WebSettings.getDefaultUserAgent(context) } returns "Device UA" andThen "Later UA"
        deviceWebViewUserAgent(context)

        deviceWebViewUserAgent(context) shouldBe "Device UA"
    }
}
