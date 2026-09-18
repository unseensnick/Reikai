package reikai.presentation.recommendation.browse

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.recommendation.RECOMMENDS_SOURCE
import reikai.domain.recommendation.RecommendationHideFilter
import reikai.domain.recommendation.RecommendationOrigin
import reikai.domain.recommendation.RelatedMangaCache
import reikai.domain.recommendation.RelatedMangaCandidate
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

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
    private fun candidate(url: String) = RelatedMangaCandidate(
        sourceId = RECOMMENDS_SOURCE,
        trackerName = "tracker",
        manga = SManga.create().apply {
            this.url = url
            title = url
        },
        origin = RecommendationOrigin.Tracker("tracker"),
    )

    private fun viewModel(): RelatedMangasBrowseViewModel {
        val cache = RelatedMangaCache()
        cache.put(MANGA_ID, carousel = emptyList(), fullPool = listOf(candidate("a"), candidate("b")))
        return RelatedMangasBrowseViewModel(
            mangaId = MANGA_ID,
            context = mockk(relaxed = true),
            relatedMangaCache = cache,
            getFavorites = mockk { coEvery { await() } returns emptyList() },
            getCategories = mockk(),
            setMangaCategories = mockk(),
            updateManga = mockk(),
            networkToLocalManga = mockk(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            buildRecommendationHideFilter = mockk {
                coEvery { await() } returns RecommendationHideFilter(
                    RecommendationHideFilter.Index.EMPTY,
                    RecommendationHideFilter.Index.EMPTY,
                    anilistTrackerId = 1L,
                    malTrackerId = 2L,
                )
            },
        )
    }

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

    private companion object {
        const val MANGA_ID = 1L
    }
}
