package reikai.presentation.reader.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TappingInvertMode
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.domain.novel.NovelTapLayout
import reikai.presentation.ComposeTestHost
import reikai.presentation.reader.navigation.MangaTapLayouts
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.Preference

/**
 * The reader sheet's tap-zone rows, which both readers share: which rows a layout shows, read with each reader's
 * own layout names and index mapping. A characterization of the shared composable, proved by mutation.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class TapZonesRowsTest(private val probe: TapZonesProbe) {

    @get:Rule
    val compose = createComposeRule()

    private fun show(layout: NovelTapLayout) = compose.setContent {
        val index = probe.indexOf(layout)
        ComposeTestHost {
            Column {
                TapZonesRows(
                    names = probe.names,
                    selected = index,
                    onSelect = {},
                    layout = probe.layoutAt(index),
                    invert = TappingInvertMode.NONE,
                    onInvert = {},
                    bottomZoneHeight = HEIGHT,
                )
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodes(hasTextExactly(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `Bottom offers only the inversions that change it`() {
        show(NovelTapLayout.BOTTOM)

        listOf("None", "Horizontal", "Vertical", "Both").filter(::shown) shouldBe listOf("None", "Vertical")
    }

    @Test
    fun `Bottom shows its zone height`() {
        show(NovelTapLayout.BOTTOM)

        compose.onNodeWithText("Bottom zone height").assertExists()
    }

    @Test
    fun `Center offers no inversion`() {
        show(NovelTapLayout.CENTER)

        compose.onNodeWithText("Invert tap zones").assertDoesNotExist()
    }

    @Test
    fun `Center has no zone height`() {
        show(NovelTapLayout.CENTER)

        compose.onNodeWithText("Bottom zone height").assertDoesNotExist()
    }

    companion object {
        private val HEIGHT: Preference<Int> = EmittingPreferenceStore().getInt("height", 12)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun probes(): List<Array<Any>> = listOf(arrayOf(MangaTapZonesProbe), arrayOf(NovelTapZonesProbe))
    }
}

/** One reader's layout names and how its stored index maps to the novel layout whose rules it follows. */
interface TapZonesProbe {
    val names: List<StringResource>
    fun indexOf(layout: NovelTapLayout): Int
    fun layoutAt(index: Int): NovelTapLayout?
}

object MangaTapZonesProbe : TapZonesProbe {
    override val names = MangaTapLayouts.names
    override fun indexOf(layout: NovelTapLayout) = (names.indices).first { MangaTapLayouts.added(it) == layout }
    override fun layoutAt(index: Int) = MangaTapLayouts.layout(index)
    override fun toString() = "manga"
}

object NovelTapZonesProbe : TapZonesProbe {
    override val names = NovelTapLayout.entries.map { it.titleRes }
    override fun indexOf(layout: NovelTapLayout) = layout.ordinal
    override fun layoutAt(index: Int) = NovelTapLayout.entries[index]
    override fun toString() = "novels"
}
