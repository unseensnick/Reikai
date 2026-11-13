package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.setNovelFlag

/** A novel keeps its own reading mode in its viewer flags, beside its rotation and rendering mode. */
class NovelPageLayoutTest {

    @ParameterizedTest
    @EnumSource(NovelPageLayout::class)
    fun `a novel's own reading mode reads back from its flags`(layout: NovelPageLayout) {
        NovelPageLayout.ofFlags(NovelPageLayout.flagOf(layout)) shouldBe layout
    }

    @Test
    fun `a novel with no reading mode of its own follows the setting`() {
        NovelPageLayout.ofFlags(0L) shouldBe null
    }

    @Test
    fun `keeping a reading mode leaves the rotation and rendering mode as they were`() {
        val rotatedWebView = 0x18L or NovelRenderingMode.flagOf(NovelRenderingMode.WEBVIEW)

        setNovelFlag(rotatedWebView, NovelPageLayout.flagOf(NovelPageLayout.PAGED_RTL), NovelPageLayout.MASK) shouldBe
            (rotatedWebView or 0x300L)
    }

    @Test
    fun `keeping a rendering mode leaves the reading mode as it was`() {
        val paged = NovelPageLayout.flagOf(NovelPageLayout.PAGED_LTR)

        NovelPageLayout.ofFlags(
            setNovelFlag(paged, NovelRenderingMode.flagOf(NovelRenderingMode.NATIVE), NovelRenderingMode.MASK),
        ) shouldBe NovelPageLayout.PAGED_LTR
    }
}
