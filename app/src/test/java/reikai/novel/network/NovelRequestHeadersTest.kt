package reikai.novel.network

import android.content.Context
import android.webkit.WebSettings
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class NovelRequestHeadersTest {

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a failed lookup of the device User-Agent is asked again next time`() {
        val context = mockk<Context>()
        mockkStatic(WebSettings::class)
        every { WebSettings.getDefaultUserAgent(context) } throws IllegalStateException("no WebView yet") andThen
            "Device UA"
        deviceWebViewUserAgent(context)

        deviceWebViewUserAgent(context) shouldBe "Device UA"
    }
}
