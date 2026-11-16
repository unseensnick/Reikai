package reikai.novel.host

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PluginPageUrlTest {

    private val site = "https://novels.example".toHttpUrl()

    @Test
    fun `a page on the plugin's own site is allowed`() {
        pluginPageUrl("https://novels.example/series/a/1", site) shouldBe
            "https://novels.example/series/a/1".toHttpUrl()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://other.example/series/a/1",
            "http://novels.example/series/a/1",
            "https://novels.example:8443/series/a/1",
            "https://sub.novels.example/series/a/1",
            "file:///data/data/app.reikai/shared_prefs/x.xml",
            "javascript:alert(1)",
        ],
    )
    fun `a page anywhere else is refused`(url: String) {
        shouldThrow<LnPluginException> { pluginPageUrl(url, site) }
    }

    @Test
    fun `nothing is allowed while the plugin declares no site`() {
        shouldThrow<LnPluginException> { pluginPageUrl("https://novels.example/series/a/1", null) }
    }
}
