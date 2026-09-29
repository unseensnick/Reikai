package reikai.presentation.library.preferredsources

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.presentation.recents.EmittingPreferenceStore

/** Both tabs of the preferred-sources screen edit their ranking through one editor. */
class SourceRankingEditorTest {

    private val preferences = ReikaiLibraryPreferences(EmittingPreferenceStore())

    private val installed = listOf(item("1"), item("2"))

    @Test
    fun `adding a manga source stores its numeric id`() = runTest {
        mangaEditor().add("42")

        preferences.preferredMangaSources.get() shouldBe listOf(42L)
    }

    @Test
    fun `a key that is not a manga id is ignored`() = runTest {
        mangaEditor().add("some-plugin")

        preferences.preferredMangaSources.get() shouldBe emptyList()
    }

    @Test
    fun `moving steps over a ranked source that is no longer installed`() = runTest {
        preferences.preferredMangaSources.set(listOf(1L, 9L, 2L))
        val editor = mangaEditor()
        editor.state.first { it is PreferredSourcesState.Success }

        editor.moveDown("1")

        preferences.preferredMangaSources.get() shouldBe listOf(2L, 9L, 1L)
    }

    @Test
    fun `removing a manga source drops it from the ranking`() = runTest {
        preferences.preferredMangaSources.set(listOf(1L, 2L))

        mangaEditor().remove("1")

        preferences.preferredMangaSources.get() shouldBe listOf(2L)
    }

    @Test
    fun `the novel editor ranks plugin slugs as they are`() = runTest {
        SourceRankingEditor(backgroundScope, flowOf(installed), preferences.preferredNovelSources) { it }
            .add("some-plugin")

        preferences.preferredNovelSources.get() shouldBe listOf("some-plugin")
    }

    private fun TestScope.mangaEditor() =
        SourceRankingEditor(backgroundScope, flowOf(installed), preferences.preferredMangaSources, String::toLongOrNull)

    private fun item(key: String) = PreferredSourceItem(key, "Source $key", "en")
}
