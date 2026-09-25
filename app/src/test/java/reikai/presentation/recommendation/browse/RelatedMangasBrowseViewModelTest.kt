package reikai.presentation.recommendation.browse

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.recommendation.RECOMMENDS_SOURCE
import reikai.domain.recommendation.RecommendationAssembly
import reikai.domain.recommendation.RecommendationHideFilter
import reikai.domain.recommendation.RecommendationOrigin
import reikai.domain.recommendation.RecommendationRanker
import reikai.domain.recommendation.RelatedMangaCache
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.domain.recommendation.RelatedPool
import reikai.domain.recommendation.TitleNormalizer
import reikai.domain.recommendation.taste.TasteProfile
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Duration.Companion.seconds

class RelatedMangasBrowseViewModelTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Tracker-origin, so an add skips it without resolving anything and goes straight to finishing. */
    private fun candidate(url: String, sourceId: Long = RECOMMENDS_SOURCE) = RelatedMangaCandidate(
        sourceId = sourceId,
        manga = SManga.create().apply {
            this.url = url
            title = url
        },
        origin = RecommendationOrigin.Tracker("tracker"),
    )

    /** The library, as a live table: a favourite write lands here and every read sees it. */
    private val favorites = MutableStateFlow<List<Manga>>(emptyList())

    private fun viewModel(
        cache: RelatedMangaCache = RelatedMangaCache().apply {
            put(MANGA_ID, RelatedPool(listOf(candidate("a"), candidate("b")), emptyMap()))
        },
        hiddenTitles: Set<String> = emptySet(),
    ): RelatedMangasBrowseViewModel = RelatedMangasBrowseViewModel(
        mangaId = MANGA_ID,
        context = mockk(relaxed = true),
        relatedMangaCache = cache,
        getFavorites = mockk {
            coEvery { await() } answers { favorites.value }
            every { subscribe(any()) } answers
                { favorites.map { list -> list.filter { it.source == firstArg<Long>() } } }
        },
        getCategories = mockk { coEvery { await() } returns emptyList() },
        setMangaCategories = mockk(relaxed = true),
        updateManga = mockk {
            coEvery { awaitUpdateFavorite(any(), true) } answers {
                favorites.update {
                    it +
                        Manga.create().copy(id = firstArg(), url = "a", source = SOURCE_ID, favorite = true)
                }
                true
            }
        },
        networkToLocalManga = mockk {
            coEvery { this@mockk.invoke(any<Manga>()) } answers { firstArg<Manga>().copy(id = 10L) }
        },
        libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        prepareRecommendationAssembly = mockk {
            coEvery { await() } returns RecommendationAssembly(
                RecommendationHideFilter(
                    RecommendationHideFilter.Index(emptySet(), emptySet(), emptySet(), hiddenTitles),
                    RecommendationHideFilter.Index.EMPTY,
                    anilistTrackerId = 1L,
                    malTrackerId = 2L,
                ),
                RecommendationRanker(),
                TasteProfile.EMPTY,
            )
        },
    )

    // The model loads on the IO dispatcher, so waits run in real time rather than the test clock.
    private suspend fun <T> settle(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { block() } }

    @Test
    fun `a selection an add cleared does not come back with the next pick`() = runTest {
        val viewModel = viewModel()
        viewModel.state.first { it.items.size == 2 }
        viewModel.toggleSelection("a")
        viewModel.addSelectedToLibrary()
        viewModel.state.first { it.selectedUrls.isEmpty() }

        viewModel.toggleSelection("b")

        viewModel.state.value.selectedUrls shouldBe setOf("b")
    }

    @Test
    fun `with no load behind it the grid settles on the empty state`() = runTest {
        val viewModel = viewModel(cache = RelatedMangaCache())

        settle { viewModel.state.first { it.content != RelatedMangasBrowseViewModel.Content.Loading } }.content shouldBe
            RelatedMangasBrowseViewModel.Content.Empty(hiddenCount = 0)
    }

    @Test
    fun `an added title stays marked in the library when the pool updates`() = runTest {
        val cache = RelatedMangaCache().apply {
            put(MANGA_ID, RelatedPool(listOf(candidate("a", SOURCE_ID)), emptyMap()), isComplete = false)
        }
        val viewModel = viewModel(cache = cache)
        settle { viewModel.state.first { it.items.isNotEmpty() } }
        viewModel.toggleSelection("a")
        viewModel.addSelectedToLibrary()
        settle { viewModel.state.first { it.selectedUrls.isEmpty() } }

        cache.put(MANGA_ID, RelatedPool(listOf(candidate("a", SOURCE_ID), candidate("b", SOURCE_ID)), emptyMap()))

        settle { viewModel.state.first { it.items.size == 2 } }
            .items.first { it.candidate.manga.url == "a" }.inLibrary shouldBe true
    }

    @Test
    fun `a pool your filters hide completely says how many are hidden`() = runTest {
        val viewModel = viewModel(hiddenTitles = setOf(TitleNormalizer.normalize("a"), TitleNormalizer.normalize("b")))

        settle { viewModel.state.first { it.items.size == 2 } }.content shouldBe
            RelatedMangasBrowseViewModel.Content.Empty(hiddenCount = 2)
    }

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 5L
    }
}
