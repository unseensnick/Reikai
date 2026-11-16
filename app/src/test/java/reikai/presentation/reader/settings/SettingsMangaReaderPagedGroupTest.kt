package reikai.presentation.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.more.settings.screen.SettingsMangaReaderScreen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.DualPageView
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainAnyOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import reikai.presentation.recents.EmittingPreferenceStore

/** The paged group of Settings > Reader applies MangaReaderSettingsRows, so it offers no row the renderer ignores. */
@RunWith(RobolectricTestRunner::class)
class SettingsMangaReaderPagedGroupTest {

    @get:Rule
    val compose = createComposeRule()

    private val singlePageRows = listOf("Crop borders", "Split wide pages")

    private class Rows(val read: @Composable () -> Unit) : Screen {
        @Composable
        override fun Content() = read()
    }

    /** The titles the paged group shows for [highQualityRenderer] under [dualPageView]. */
    private fun shown(highQualityRenderer: Boolean, dualPageView: DualPageView): List<String> {
        val preferences = ReaderPreferences(EmittingPreferenceStore())
        val rows = MangaReaderSettingsRows(highQualityRenderer, dualPageView)
        var titles = emptyList<String>()
        compose.setContent {
            ComposeTestHost {
                Navigator(
                    Rows {
                        titles = SettingsMangaReaderScreen.getPagedGroup(preferences, rows).preferenceItems
                            .filter { it.visible }
                            .map { it.title }
                    },
                )
            }
        }
        compose.waitForIdle()
        return titles
    }

    @Test
    fun `the high quality renderer always showing two pages hides the single page rows`() {
        shown(highQualityRenderer = true, DualPageView.ALWAYS) shouldNotContainAnyOf singlePageRows
    }

    @Test
    fun `the classic renderer keeps the single page rows`() {
        shown(highQualityRenderer = false, DualPageView.ALWAYS) shouldContainAll singlePageRows
    }
}
