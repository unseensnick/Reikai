package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.setNovelFlag

/**
 * Both settings surfaces show the chapter-markup rows (embedded CSS and JS, snippets, fonts) by this, and a
 * novel keeps its own mode in its viewer flags.
 */
class NovelRenderingModeTest {

    @Test
    fun `the native renderer draws no chapter markup`() {
        NovelRenderingMode.NATIVE.rendersMarkup shouldBe false
    }

    @Test
    fun `the webview renderer draws the chapter's markup`() {
        NovelRenderingMode.WEBVIEW.rendersMarkup shouldBe true
    }

    @ParameterizedTest
    @EnumSource(NovelRenderingMode::class)
    fun `a novel's own mode reads back from its flags`(mode: NovelRenderingMode) {
        NovelRenderingMode.ofFlags(NovelRenderingMode.flagOf(mode)) shouldBe mode
    }

    @Test
    fun `a novel with no mode of its own follows the setting`() {
        NovelRenderingMode.ofFlags(0L) shouldBe null
    }

    @Test
    fun `keeping a mode leaves the novel's rotation as it was`() {
        val rotated = 0x18L

        setNovelFlag(rotated, NovelRenderingMode.flagOf(NovelRenderingMode.WEBVIEW), NovelRenderingMode.MASK) shouldBe
            (rotated or 0x80L)
    }
}
