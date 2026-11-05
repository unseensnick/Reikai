package reikai.presentation.settings.download

import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaPreferences
import reikai.domain.novel.NovelPreferences
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.source.service.SourceManager

/** The per-source delays list: which sources it shows under the chip, the search and the Show setting. */
class SourceDelaysViewModelTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val store = EmittingPreferenceStore()
    private val library = ReikaiLibraryPreferences(store)
    private val mangaPreferences = MangaPreferences(store)

    private val weebCentral = mockk<HttpSource> {
        every { id } returns 7L
        every { name } returns "Weeb Central"
        every { lang } returns "en"
    }
    private val fenrir = mockk<NovelSource> {
        every { id } returns "fenrir"
        every { name } returns "Fenrir Realm"
        every { lang } returns "en"
        every { minimumRequestDelayMs } returns 0L
    }

    private fun TestScope.model(): SourceDelaysViewModel {
        val model = main.track(
            SourceDelaysViewModel(
                sourceManager = mockk { every { sources } returns MutableStateFlow(listOf(weebCentral)) },
                novelSourceManager = mockk<NovelSourceManager> {
                    every { loadedSources() } returns MutableStateFlow(listOf(fenrir))
                },
                reikaiLibraryPreferences = library,
                mangaPreferences = mangaPreferences,
                novelPreferences = NovelPreferences(store),
            ),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        return model
    }

    private suspend fun SourceDelaysViewModel.names(): List<String> {
        val sections = state.first { it.sections != null }.sections!!
        return (sections.own + sections.manga + sections.novels).map { it.name }
    }

    @Test
    fun `both types are listed while both show`() = runTest {
        model().names() shouldBe listOf("Weeb Central", "Fenrir Realm")
    }

    @Test
    fun `the chip narrows the list to one type`() = runTest {
        val model = model()

        model.setContentType(ContentType.NOVELS)

        model.names() shouldBe listOf("Fenrir Realm")
    }

    /** The chip row hides itself then, so a chip left on Novels must not empty the list. */
    @Test
    fun `hiding a type while the screen is open follows the Show setting, not the hidden chip`() = runTest {
        val model = model()
        model.setContentType(ContentType.NOVELS)

        library.shownContent.set(ContentType.MANGA)

        model.names() shouldBe listOf("Weeb Central")
    }

    @Test
    fun `showing both again brings back the chip last picked`() = runTest {
        val model = model()
        model.setContentType(ContentType.NOVELS)
        library.shownContent.set(ContentType.MANGA)

        library.shownContent.set(ContentType.ALL)

        model.names() shouldBe listOf("Fenrir Realm")
    }

    @Test
    fun `the search keeps the sources whose name matches`() = runTest {
        val model = model()

        model.setQuery("weeb")

        model.names() shouldBe listOf("Weeb Central")
    }

    @Test
    fun `a source with its own delay is listed first`() = runTest {
        mangaPreferences.downloadSourceDelays().set(setOf("7=3000"))
        val model = model()

        model.state.first { it.sections != null }.sections!!.own.map { it.name } shouldBe listOf("Weeb Central")
    }

    @Test
    fun `a manga source's delay is saved under manga, keyed by its id`() = runTest {
        val model = model()
        val row = model.state.first { it.sections != null }.sections!!.manga.single()

        model.setDelay(row, 5_000L)

        mangaPreferences.downloadSourceDelays().get() shouldBe setOf("7=5000")
    }

    @Test
    fun `choosing Default hands the source back to the global delay`() = runTest {
        mangaPreferences.downloadSourceDelays().set(setOf("7=3000"))
        val model = model()
        val row = model.state.first { it.sections != null }.sections!!.own.single()

        model.setDelay(row, null)

        mangaPreferences.downloadSourceDelays().get() shouldBe emptySet()
    }
}
