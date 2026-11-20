package reikai.presentation.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainAnyOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.i18n.MR

/** Both reader settings screens' progress rows, which show the bar's style only while the bar draws. */
@RunWith(RobolectricTestRunner::class)
class ReaderProgressPreferenceItemsTest {

    @get:Rule
    val compose = createComposeRule()

    private val styleRows = listOf("Progress size", "Background behind progress")

    private class Rows(val read: @Composable () -> Unit) {
        @Composable
        fun Content() = read()
    }

    private fun shown(showProgress: Boolean, showTimeLeft: Boolean): List<String> {
        val preferences = ReaderPreferences(EmittingPreferenceStore()).apply {
            showPageNumber.set(showProgress)
            this.showTimeLeft.set(showTimeLeft)
        }
        var titles = emptyList<String>()
        compose.setContent {
            ComposeTestHost {
                Rows {
                    titles = readerProgressPreferenceItems(
                        progressLabel = MR.strings.pref_show_page_number,
                        showProgress = preferences.showPageNumber,
                        showTimeLeft = preferences.showTimeLeft,
                        progressSize = preferences.progressSize,
                        progressBacked = preferences.progressBacked,
                    ).filter { it.visible }.map { it.title }
                }.Content()
            }
        }
        compose.waitForIdle()
        return titles
    }

    @Test
    fun `with neither progress nor time left shown the bar's style rows are hidden`() {
        shown(showProgress = false, showTimeLeft = false) shouldNotContainAnyOf styleRows
    }

    @Test
    fun `time left alone shows the bar's style rows`() {
        shown(showProgress = false, showTimeLeft = true) shouldContainAll styleRows
    }
}
