package reikai.novel.content

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import reikai.novel.source.NovelChapterTextLoader
import reikai.presentation.recents.EmittingPreferenceStore

@OptIn(ExperimentalCoroutinesApi::class)
class NovelContentConfigTest {

    /**
     * The open chapter re-runs the pipeline only when [NovelContentConfig.changes] fires, so a setting
     * the config reads but the stream does not watch reaches the page only on the next open. Neither
     * store is collected, so the watched keys are the ones subscribed to, never ones read on emission.
     */
    @Test
    fun `every setting the config reads is one its change stream watches`() {
        val read = EmittingPreferenceStore().also { NovelContentConfig.from(NovelPreferences(it), null, "") }
        val watched = EmittingPreferenceStore().also { NovelContentConfig.changes(NovelPreferences(it)) }

        watched.getAll().keys shouldBe read.getAll().keys
    }

    @Test
    fun `a WebView reader gets content for a WebView page`() {
        val preferences = NovelPreferences(EmittingPreferenceStore())
        preferences.readerRenderingMode().set(NovelRenderingMode.WEBVIEW)

        NovelContentConfig.from(preferences, null, "").target shouldBe RenderTarget.WEB_VIEW
    }

    @Test
    fun `flipping hide chapter title reaches the open chapter`() = runTest {
        val preferences = NovelPreferences(EmittingPreferenceStore())
        val loader = NovelChapterTextLoader(
            context = mockk(relaxed = true),
            novelRepo = mockk(relaxed = true),
            sourceManager = mockk(relaxed = true),
            installer = mockk(relaxed = true),
            preferences = preferences,
            readDownloaded = { _, _ -> null },
        )
        var emissions = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            loader.settingsChanged.collect { emissions++ }
        }

        preferences.readerHideChapterTitle().set(true)

        emissions shouldBe 1
    }
}
