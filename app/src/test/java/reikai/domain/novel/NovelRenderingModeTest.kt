package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Both settings surfaces show the chapter-markup rows (embedded CSS and JS, snippets, fonts) by this. */
class NovelRenderingModeTest {

    @Test
    fun `the native renderer draws no chapter markup`() {
        NovelRenderingMode.NATIVE.rendersMarkup shouldBe false
    }

    @Test
    fun `the webview renderer draws the chapter's markup`() {
        NovelRenderingMode.WEBVIEW.rendersMarkup shouldBe true
    }
}
