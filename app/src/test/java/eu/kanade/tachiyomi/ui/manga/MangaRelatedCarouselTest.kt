package eu.kanade.tachiyomi.ui.manga

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.recommendation.EnabledRecommendationStreams
import reikai.domain.recommendation.PrepareRecommendationAssembly
import reikai.domain.recommendation.RecommendationAssembly
import reikai.domain.recommendation.RecommendationHideFilter
import reikai.domain.recommendation.RecommendationOrigin
import reikai.domain.recommendation.RecommendationRanker
import reikai.domain.recommendation.RelatedLibraryKeys
import reikai.domain.recommendation.RelatedMangaCache
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.domain.recommendation.RelatedPool
import reikai.domain.recommendation.taste.TasteProfile
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Instant

/** The details carousel marks its cards from the live library, as its "See all" grid does. Manga only. */
class MangaRelatedCarouselTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Both read through Injekt in production; held at their shipped defaults.
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter(any()) } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `a card added to the library after the carousel loaded is marked in it`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        driver.seedManga(MANGA_ID, chapterId = 10L, sourceId = SOURCE_ID)
        val favorites = MutableStateFlow<List<Manga>>(emptyList())
        val getFavorites = mockk<GetFavorites> {
            every { subscribe(any()) } answers { favorites.map { all -> all.filter { it.source == firstArg<Long>() } } }
        }
        val cache = RelatedMangaCache().apply { put(MANGA_ID, RelatedPool(listOf(candidate("a")), emptyMap())) }
        val store = ViewModelStore()
        val model = mangaDetailsModel(
            MANGA_ID,
            MangaRepositoryImpl(database),
            ChapterRepositoryImpl(database),
            longArrayOf(MANGA_ID),
            sourceManager = mockk<SourceManager>(relaxed = true) { coEvery { getOrStub(any()) } returns httpSource() },
            relatedMangaCache = cache,
            relatedLibraryKeys = RelatedLibraryKeys(getFavorites),
            prepareRecommendationAssembly = noHiding(),
        ).also { store.put("manga", it) }
        val marked = try {
            withContext(Dispatchers.Default) {
                val success = model.state.filterIsInstance<MangaViewModel.State.Success>()
                withTimeout(30_000) { success.first() }
                model.loadRelatedMangas()
                withTimeout(10_000) { success.first { it.relatedItems.size == 1 } }
                favorites.value = listOf(
                    Manga.create().copy(
                        id = 50L,
                        url = "a",
                        source = SOURCE_ID,
                        favoriteAt = Instant.fromEpochMilliseconds(0L),
                    ),
                )
                withTimeoutOrNull(5_000) { success.first { it.relatedItems.single().inLibrary } } != null
            }
        } finally {
            // The model reads on the real IO dispatcher, so it stops before the database closes.
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            job.join()
            driver.close()
        }

        marked shouldBe true
    }

    private fun candidate(url: String) = RelatedMangaCandidate(
        sourceId = SOURCE_ID,
        manga = SManga.create().apply {
            this.url = url
            title = url
        },
        origin = RecommendationOrigin.Tracker("tracker"),
        trackerId = ANILIST_ID,
    )

    /** An assembly that hides nothing and, built before any add, holds an empty library. */
    private fun noHiding(): PrepareRecommendationAssembly = mockk {
        coEvery { await() } returns RecommendationAssembly(
            RecommendationHideFilter(
                RecommendationHideFilter.Index.EMPTY,
                hidesInLibrary = false,
                RecommendationHideFilter.Index.EMPTY,
                anilistTrackerId = ANILIST_ID,
                malTrackerId = MAL_ID,
            ),
            RecommendationRanker(),
            TasteProfile.EMPTY,
            EnabledRecommendationStreams(setOf(ANILIST_ID), crossRecs = true, tagSearch = true),
        )
    }

    private fun httpSource() = mockk<HttpSource>(relaxed = true) {
        every { id } returns SOURCE_ID
        every { name } returns "src"
    }

    private companion object {
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        const val MANGA_ID = 1L
        const val SOURCE_ID = 11L
        const val ANILIST_ID = 1L
        const val MAL_ID = 2L
    }
}
