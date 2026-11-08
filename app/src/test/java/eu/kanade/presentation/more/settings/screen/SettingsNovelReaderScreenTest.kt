package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelPreferences
import reikai.presentation.ComposeTestHost
import reikai.presentation.recents.EmittingPreferenceStore

/** Settings > Novel reader's Reading group offers Dual page view where Settings > Manga reader does. */
@RunWith(RobolectricTestRunner::class)
class SettingsNovelReaderScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun readingRows(layout: NovelPageLayout): List<String> {
        val preferences = NovelPreferences(EmittingPreferenceStore()).apply { readerPageLayout().set(layout) }
        var titles = emptyList<String>()
        // Under a navigator, which the bottom buttons row opens its screen through.
        compose.setContent { ComposeTestHost { Navigator(ReadingRows(preferences) { titles = it }) } }
        compose.waitForIdle()
        return titles
    }

    @Test
    fun `paged reading offers dual page view`() {
        readingRows(NovelPageLayout.PAGED_LTR) shouldContain DUAL_PAGE_VIEW
    }

    @Test
    fun `scrolling offers no dual page view`() {
        readingRows(NovelPageLayout.SCROLL) shouldNotContain DUAL_PAGE_VIEW
    }

    private class ReadingRows(val preferences: NovelPreferences, val read: (List<String>) -> Unit) : Screen {
        @Composable
        override fun Content() {
            read(SettingsNovelReaderScreen.getReadingGroup(preferences).preferenceItems.map { it.title })
        }
    }

    private companion object {
        const val DUAL_PAGE_VIEW = "Dual page view"
    }
}
