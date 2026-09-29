package eu.kanade.tachiyomi.ui.deeplink

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.source.NovelLinkTarget
import reikai.domain.source.ResolveMangaLink
import reikai.domain.source.ResolveNovelLink
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/** The order a shared link is tried in: link hooks, then stored rows, then guesses, manga before novel in each. */
class DeepLinkViewModelTest {

    private val manga = Manga.create().copy(id = 1L)
    private val novel = NovelLinkTarget.Novel("plugin", "novel/1")

    private val mangaLinks = mockk<ResolveMangaLink> {
        coEvery { bySearch(any()) } returns null
        coEvery { byStoredRow(any()) } returns null
        coEvery { byGuess(any()) } returns null
    }
    private val novelLinks = mockk<ResolveNovelLink> {
        coEvery { byHook(any()) } returns null
        coEvery { byStoredRow(any()) } returns null
        coEvery { byGuess(any()) } returns null
    }
    private var mangaSources: List<Source> = emptyList()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // The model resolves on the IO dispatcher from init, so this suspends until that settles.
    private suspend fun resolved(): DeepLinkViewModel.State {
        val model = DeepLinkViewModel(
            LINK,
            mockk<SourceManager> { coEvery { getAll() } returns mangaSources },
            mockk { coEvery { this@mockk.invoke(any<Manga>()) } returns manga },
            mockk(),
            mockk(),
            mangaLinks,
            novelLinks,
        )
        return model.state.first { it !is DeepLinkViewModel.State.Loading }
    }

    @Test
    fun `an extension that reads the link opens it before any other answer`() = runTest {
        mangaSources = listOf(
            mockk<ResolvableSource> {
                every { id } returns 1L
                every { getUriType(LINK) } returns UriType.Manga
                coEvery { getManga(LINK) } returns SManga.create().apply {
                    url = "/series/1"
                    title = "Series"
                }
            },
        )
        coEvery { mangaLinks.bySearch(LINK) } returns manga.copy(id = 2L)

        resolved() shouldBe DeepLinkViewModel.State.Result(manga)
    }

    @Test
    fun `a manga the site's search finds beats a novel source that reads the link`() = runTest {
        coEvery { mangaLinks.bySearch(LINK) } returns manga
        coEvery { novelLinks.byHook(LINK) } returns novel

        resolved() shouldBe DeepLinkViewModel.State.Result(manga)
    }

    @Test
    fun `a novel source that reads the link beats a stored manga`() = runTest {
        coEvery { novelLinks.byHook(LINK) } returns novel
        coEvery { mangaLinks.byStoredRow(LINK) } returns manga

        resolved() shouldBe DeepLinkViewModel.State.NovelResult("plugin", "novel/1")
    }

    @Test
    fun `a stored manga beats a stored novel`() = runTest {
        coEvery { mangaLinks.byStoredRow(LINK) } returns manga
        coEvery { novelLinks.byStoredRow(LINK) } returns novel

        resolved() shouldBe DeepLinkViewModel.State.Result(manga)
    }

    @Test
    fun `a stored novel beats a guessed manga`() = runTest {
        coEvery { novelLinks.byStoredRow(LINK) } returns novel
        coEvery { mangaLinks.byGuess(LINK) } returns manga

        resolved() shouldBe DeepLinkViewModel.State.NovelResult("plugin", "novel/1")
    }

    @Test
    fun `a guessed manga beats a guessed novel`() = runTest {
        coEvery { mangaLinks.byGuess(LINK) } returns manga
        coEvery { novelLinks.byGuess(LINK) } returns novel

        resolved() shouldBe DeepLinkViewModel.State.Result(manga)
    }

    @Test
    fun `a novel guess opens when nothing else answers`() = runTest {
        coEvery { novelLinks.byGuess(LINK) } returns novel

        resolved() shouldBe DeepLinkViewModel.State.NovelResult("plugin", "novel/1")
    }

    @Test
    fun `a chapter a novel source reads opens in the reader`() = runTest {
        coEvery { novelLinks.byHook(LINK) } returns NovelLinkTarget.Chapter(3L, 4L)

        resolved() shouldBe DeepLinkViewModel.State.NovelChapterResult(3L, 4L)
    }

    @Test
    fun `a link nothing answers falls to search`() = runTest {
        resolved() shouldBe DeepLinkViewModel.State.NoResults
    }

    private companion object {
        const val LINK = "https://example.com/novel/1"
    }
}
