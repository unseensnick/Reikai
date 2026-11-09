package reikai.presentation.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Which plugins' copy of a site's sign-in Clear cookies and Clear WebView data forget. */
class PluginWebStorageClearTest {

    @Test
    fun `a plugin keeping a site's storage is forgotten once, whatever else it stores`() {
        val keys = listOf(
            "ln_storage::novelsite::webview:local",
            "ln_storage::novelsite::webview:session",
            "ln_storage::novelsite::storage:theme",
            "ln_storage::other::storage:theme",
            "ireader_storage::app.ext::webview:local",
            "pref_theme",
        )

        pluginsKeepingWebStorage(keys) shouldBe setOf("novelsite")
    }
}
