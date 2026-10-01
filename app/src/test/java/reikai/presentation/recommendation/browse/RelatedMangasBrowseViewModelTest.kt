package reikai.presentation.recommendation.browse

import android.content.Context
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.recommendation.BuildRecommendationHideFilter
import reikai.domain.recommendation.EnabledRecommendationStreams
import reikai.domain.recommendation.PrepareRecommendationAssembly
import reikai.domain.recommendation.RECOMMENDS_SOURCE
import reikai.domain.recommendation.RecommendationAssembly
import reikai.domain.recommendation.RecommendationHideFilter
import reikai.domain.recommendation.RecommendationOrigin
import reikai.domain.recommendation.RecommendationRanker
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.RelatedMangaCache
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.domain.recommendation.RelatedPool
import reikai.domain.recommendation.TitleNormalizer
import reikai.domain.recommendation.taste.TasteProfile
import reikai.presentation.browse.FakeMangaLibrary
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import kotlin.time.Duration.Companion.seconds

class RelatedMangasBrowseViewModelTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Spied, not stubbed: unstubbed lookups run as before, and a case can check the message it asked for.
        mockkStatic(LOCALIZE)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(LOCALIZE)
        Dispatchers.resetMain()
    }

    /** Tracker-origin, so an add skips it without resolving anything and goes straight to finishing. */
    private fun candidate(
        url: String,
        sourceId: Long = RECOMMENDS_SOURCE,
        title: String = url,
        origin: RecommendationOrigin = RecommendationOrigin.Tracker("tracker"),
    ) = RelatedMangaCandidate(
        sourceId = sourceId,
        manga = SManga.create().apply {
            this.url = url
            this.title = title
        },
        origin = origin,
        trackerId = ANILIST_ID,
    )

    private val trackerManager: TrackerManager = mockk {
        every { aniList.id } returns ANILIST_ID
        every { myAnimeList.id } returns 2L
        every { mangaUpdates.id } returns 3L
        every { shikimori.id } returns 4L
    }

    /** The library, as a live table: a favourite write lands here and every read sees it. */
    private val library = FakeMangaLibrary()

    // One stored row per url, numbered from 10 in the order the grid resolves them.
    private val localIds = mutableMapOf<String, Long>()

    // Emitting, so a preference written while the grid is open reaches a model that follows it.
    private val store = EmittingPreferenceStore()

    private val getFavorites: GetFavorites = mockk {
        coEvery { await() } answers { library.rows.values.filter { it.favorite } }
        every { subscribe(any()) } answers
            { library.favorites.map { list -> list.filter { it.source == firstArg<Long>() } } }
    }

    private fun hiding(titles: Set<String>): PrepareRecommendationAssembly = mockk {
        coEvery { await() } returns RecommendationAssembly(
            RecommendationHideFilter(
                RecommendationHideFilter.Index.EMPTY,
                hidesInLibrary = false,
                RecommendationHideFilter.Index(emptySet(), emptySet(), emptySet(), emptySet(), titles),
                anilistTrackerId = ANILIST_ID,
                malTrackerId = 2L,
            ),
            RecommendationRanker(),
            TasteProfile.EMPTY,
            EnabledRecommendationStreams(setOf(ANILIST_ID), crossRecs = true, tagSearch = true),
        )
    }

    /** The assembly as the app builds it, reading the library above and the recommendation settings. */
    private fun assemblyFromLibrary(): PrepareRecommendationAssembly {
        val preferences = ReikaiRecommendationPreferences(store)
        return PrepareRecommendationAssembly(
            preferences = preferences,
            getTasteProfile = mockk { coEvery { await() } returns TasteProfile.EMPTY },
            buildHideFilter = BuildRecommendationHideFilter(
                getFavorites = getFavorites,
                getTracksPerManga = mockk { every { subscribe() } returns flowOf(emptyMap()) },
                repository = mockk { coEvery { getAll() } returns emptyList() },
                preferences = preferences,
                localTrackStatusMapper = mockk(),
                trackerManager = trackerManager,
            ),
            trackerManager = trackerManager,
        )
    }

    private fun viewModel(
        cache: RelatedMangaCache = RelatedMangaCache().apply {
            put(MANGA_ID, RelatedPool(listOf(candidate("a"), candidate("b")), emptyMap()))
        },
        assembly: PrepareRecommendationAssembly = hiding(emptySet()),
    ): RelatedMangasBrowseViewModel = RelatedMangasBrowseViewModel(
        mangaId = MANGA_ID,
        context = mockk(relaxed = true),
        relatedMangaCache = cache,
        getFavorites = getFavorites,
        getCategories = library.getCategories,
        libraryAdder = library.adder,
        networkToLocalManga = mockk {
            coEvery { this@mockk.invoke(any<Manga>()) } answers {
                val manga = firstArg<Manga>()
                library.insert(manga.copy(id = localIds.getOrPut(manga.url) { 10L + localIds.size }))
            }
        },
        libraryPreferences = LibraryPreferences(store),
        prepareRecommendationAssembly = assembly,
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
    fun `a related add stamps the default chapter settings`() = runTest {
        val cache = RelatedMangaCache().apply {
            put(MANGA_ID, RelatedPool(listOf(candidate("a", SOURCE_ID)), emptyMap()))
        }
        val viewModel = viewModel(cache = cache)
        settle { viewModel.state.first { it.items.isNotEmpty() } }
        viewModel.toggleSelection("a")
        viewModel.addSelectedToLibrary()
        settle { viewModel.state.first { it.selectedUrls.isEmpty() } }

        library.chapterDefaultsStamped shouldBe setOf(10L)
    }

    @Test
    fun `a pool your filters hide completely says how many are hidden`() = runTest {
        val viewModel =
            viewModel(assembly = hiding(setOf(TitleNormalizer.normalize("a"), TitleNormalizer.normalize("b"))))

        settle { viewModel.state.first { it.items.size == 2 } }.content shouldBe
            RelatedMangasBrowseViewModel.Content.Empty(hiddenCount = 2)
    }

    @Test
    fun `a library title the source lists under another url is marked in the library`() = runTest {
        library.insert(
            Manga.create().copy(
                id = 26L,
                url = "/series/1/Slug",
                source = SOURCE_ID,
                title = "Series",
                favoriteAt = 1L,
            ),
        )
        val cache = RelatedMangaCache().apply {
            put(MANGA_ID, RelatedPool(listOf(candidate("/series/1", SOURCE_ID, title = "Series")), emptyMap()))
        }
        val viewModel = viewModel(cache = cache, assembly = assemblyFromLibrary())

        settle { viewModel.state.first { it.items.isNotEmpty() } }.items.single().inLibrary shouldBe true
    }

    @Test
    fun `with the library filter on a library title under another url is hidden`() = runTest {
        ReikaiRecommendationPreferences(store).hideInLibraryRecommendations.set(true)
        library.insert(
            Manga.create().copy(
                id = 26L,
                url = "/series/1/Slug",
                source = SOURCE_ID,
                title = "Series",
                favoriteAt = 1L,
            ),
        )
        val cache = RelatedMangaCache().apply {
            put(MANGA_ID, RelatedPool(listOf(candidate("/series/1", SOURCE_ID, title = "Series")), emptyMap()))
        }
        val viewModel = viewModel(cache = cache, assembly = assemblyFromLibrary())

        settle { viewModel.state.first { it.items.isNotEmpty() } }.content shouldBe
            RelatedMangasBrowseViewModel.Content.Empty(hiddenCount = 1)
    }

    /** b resolves second, to row 11, whose favorite write the library refuses. */
    @Test
    fun `a title whose library write fails is not counted as added`() = runTest {
        library.refusedFavoriteWrites += 11L
        val cache = RelatedMangaCache().apply {
            put(MANGA_ID, RelatedPool(listOf(candidate("a", SOURCE_ID), candidate("b", SOURCE_ID)), emptyMap()))
        }
        val viewModel = viewModel(cache = cache)
        settle { viewModel.state.first { it.items.size == 2 } }
        viewModel.toggleSelection("a")
        viewModel.toggleSelection("b")

        viewModel.addSelectedToLibrary()

        verify(timeout = 5_000) { any<Context>().stringResource(MR.strings.bulk_added_with_skipped, 1, 1) }
    }

    /** Grouped, the grid draws a1 a2 under one header and b1 b2 under the next, though the rank interleaves them. */
    @Test
    fun `range select in the grouped grid follows the order on screen`() = runTest {
        val first = RecommendationOrigin.Tracker("first")
        val second = RecommendationOrigin.Tracker("second")
        val cache = RelatedMangaCache().apply {
            put(
                MANGA_ID,
                RelatedPool(
                    listOf(
                        candidate("a1", origin = first),
                        candidate("b1", origin = second),
                        candidate("a2", origin = first),
                        candidate("b2", origin = second),
                    ),
                    emptyMap(),
                ),
            )
        }
        val viewModel = viewModel(cache = cache)
        settle { viewModel.state.first { it.items.size == 4 } }
        viewModel.toggleGrouping()

        viewModel.toggleSelection("a1")
        viewModel.toggleRangeSelection("a2")

        viewModel.state.value.selectedUrls shouldBe setOf("a1", "a2")
    }

    @Test
    fun `a column count changed while the grid is open reaches it`() = runTest {
        val viewModel = viewModel()

        LibraryPreferences(store).portraitColumns.set(4)

        settle { viewModel.state.first { it.columns.portrait == 4 } }.columns.portrait shouldBe 4
    }

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 5L
        const val ANILIST_ID = 1L
        const val LOCALIZE = "tachiyomi.core.common.i18n.LocalizeKt"
    }
}
