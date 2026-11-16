package reikai.presentation.reader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelRenderingMode
import reikai.presentation.ComposeTestHost

/** The novel bar's reading-mode picker, over the same selection rules as manga's (ModeSelection). */
@RunWith(RobolectricTestRunner::class)
class ReaderPageLayoutDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val written = mutableListOf<NovelPageLayout?>()

    private fun show(own: NovelPageLayout?, effective: NovelPageLayout) {
        compose.setContent {
            ComposeTestHost {
                ReaderPageLayoutDialog(Layouts(own, effective), onChange = { written += it }, onDismiss = {})
            }
        }
    }

    @Test
    fun `a tapped reading mode is written as the novel's own`() {
        show(own = null, effective = NovelPageLayout.SCROLL)

        compose.onNodeWithText("Paged (left to right)").performClick()
        compose.onNodeWithText("Apply").performClick()

        written shouldBe listOf(NovelPageLayout.PAGED_LTR)
    }

    /** The highlighted tile is the default's for a novel on Default, so applying it untouched pins nothing. */
    @Test
    fun `an untouched Apply writes nothing`() {
        show(own = null, effective = NovelPageLayout.SCROLL)

        compose.onNodeWithText("Apply").performClick()

        written shouldBe emptyList()
    }

    @Test
    fun `revert to default clears the novel's own reading mode`() {
        show(own = NovelPageLayout.PAGED_RTL, effective = NovelPageLayout.PAGED_RTL)

        compose.onNodeWithText("Revert to default").performClick()

        written shouldBe listOf(null)
    }

    private class Layouts(own: NovelPageLayout?, effective: NovelPageLayout) : ReaderTextSettings {
        override val state = MutableStateFlow(ReaderTextState(18, true, "#000000", "#FFFFFF"))
        override fun setFontSize(size: Int) = Unit
        override fun followSystemTheme() = Unit
        override fun setThemeColors(background: String, textColor: String) = Unit
        override val renderingMode = MutableStateFlow<NovelRenderingMode?>(null)
        override fun setRenderingMode(mode: NovelRenderingMode?) = Unit
        override val pageLayout = MutableStateFlow(own)
        override val effectivePageLayout = MutableStateFlow(effective)
        override fun setPageLayout(layout: NovelPageLayout?) = Unit
    }
}
