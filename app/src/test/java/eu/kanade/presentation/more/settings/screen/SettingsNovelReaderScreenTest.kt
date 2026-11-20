package eu.kanade.presentation.more.settings.screen

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import eu.kanade.presentation.more.settings.Preference
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
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
        compose.setContent { ComposeTestHost { ReadingRows(preferences) { titles = it }.Content() } }
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

    /** Beside the manga reader's own row, in the same place on its screen. */
    @Test
    fun `reading offers show reading mode`() {
        readingRows(NovelPageLayout.SCROLL) shouldContain "Show reading mode"
    }

    /**
     * A novel kept on WebView under a native default reads these too, and the sheet has no row for them.
     * Read off the whole screen, so a gate on the group, its rows or the list that adds it shows here.
     */
    @Test
    fun `the WebView-only rows show under a native default`() {
        val preferences = NovelPreferences(EmittingPreferenceStore())
            .apply { readerRenderingMode().set(NovelRenderingMode.NATIVE) }
        val context = Graphed(ApplicationProvider.getApplicationContext(), preferences)
        var titles = emptyList<String>()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                ComposeTestHost {
                    Rows({
                        SettingsNovelReaderScreen.getPreferences()
                            .filterIsInstance<Preference.PreferenceGroup>()
                            .filter { it.visible }
                            .flatMap { group -> group.preferenceItems.filter { it.visible }.map { it.title } }
                    }) { titles = it }.Content()
                }
            }
        }
        compose.waitForIdle()

        titles shouldContainAll listOf(RUN_SCRIPTS, "CSS snippets", "JavaScript snippets")
    }

    private class Graphed(base: Context, preferences: NovelPreferences) :
        ContextWrapper(base),
        GraphProvider<AppGraph> {
        override val graph: AppGraph = mockk(relaxed = true) { every { novelPreferences } returns preferences }

        override fun getApplicationContext(): Context = this
    }

    private class ReadingRows(val preferences: NovelPreferences, val read: (List<String>) -> Unit) {
        @Composable
        fun Content() {
            read(
                SettingsNovelReaderScreen.getReadingGroup(preferences).preferenceItems
                    .filter { it.visible }
                    .map { it.title },
            )
        }
    }

    private class Rows(val rows: @Composable () -> List<String>, val read: (List<String>) -> Unit) {
        @Composable
        fun Content() {
            read(rows())
        }
    }

    private companion object {
        const val DUAL_PAGE_VIEW = "Dual page view"
        const val RUN_SCRIPTS = "Run scripts a chapter embeds"
    }
}
