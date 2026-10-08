package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.MangaPreferences
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.track.EntryTrackPort
import reikai.domain.track.EntryTrackPorts
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The details model holds the entry's chapter query open only while its state is collected, plus five seconds,
 * as upstream's stateIn(WhileSubscribed(5.seconds)) does. A real [MangaViewModel] over an in-memory database;
 * the chapter repository counts live collectors of the opened entry's chapter flow, the query the list reads.
 * `launchIO` hard-codes Dispatchers.IO, so IO is pointed at the test dispatcher to keep time virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MangaViewModelSubscriptionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns dispatcher
        // Both read through Injekt in production; held at their shipped defaults.
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing collecting the state leaves the chapter query unsubscribed`() = runTest(dispatcher) {
        withModel { _, chapters ->
            advanceUntilIdle()

            chapters.active.get() shouldBe 0
        }
    }

    @Test
    fun `a collected state holds the chapter query open`() = runTest(dispatcher) {
        withModel { model, chapters ->
            backgroundScope.launch { model.state.collect {} }
            advanceUntilIdle()

            chapters.active.get() shouldBe 1
        }
    }

    @Test
    fun `the chapter query is still held just under five seconds after the last collector leaves`() =
        runTest(dispatcher) {
            withModel { model, chapters ->
                val collector = launch { model.state.collect {} }
                advanceUntilIdle()

                collector.cancel()
                advanceTimeBy(4.9.seconds)
                runCurrent()

                chapters.active.get() shouldBe 1
            }
        }

    @Test
    fun `the chapter query is released five seconds after the last collector leaves`() = runTest(dispatcher) {
        withModel { model, chapters ->
            val collector = launch { model.state.collect {} }
            advanceUntilIdle()

            collector.cancel()
            advanceTimeBy(5.seconds)
            runCurrent()

            chapters.active.get() shouldBe 0
        }
    }

    @Test
    fun `a sibling source's download progress shows on a merged series' All view`() = runTest(dispatcher) {
        val progress = MutableSharedFlow<Download>(extraBufferCapacity = 1)
        withModel(merged = true, progress = progress) { model, _ ->
            backgroundScope.launch { model.state.collect {} }
            advanceUntilIdle()

            progress.tryEmit(siblingDownload)
            advanceUntilIdle()

            val row = (model.state.value as MangaViewModel.State.Success).chapters.single { it.id == SIBLING_CHAPTER }
            row.downloadProgress shouldBe 40
        }
    }

    private val siblingDownload = mockk<Download> {
        every { manga } returns Manga.create().copy(id = SIBLING_ID, source = 1L)
        every { chapter } returns Chapter.create().copy(id = SIBLING_CHAPTER, mangaId = SIBLING_ID)
        every { status } returns Download.State.DOWNLOADING
        every { progress } returns 40
    }

    private suspend fun TestScope.withModel(
        merged: Boolean = false,
        progress: Flow<Download> = emptyFlow(),
        block: suspend TestScope.(MangaViewModel, CountingChapters) -> Unit,
    ) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        val mangas = MangaRepositoryImpl(database)
        val chapters = CountingChapters(ChapterRepositoryImpl(database), watched = MANGA_ID)
        listOf(MANGA_ID to 10L, SIBLING_ID to SIBLING_CHAPTER).forEach { (mangaId, chapterId) ->
            driver.execute(
                null,
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "($mangaId, 1, '/manga$mangaId', 'Title', 0, 1, 0, 0, 0, 1, 0, 0, '', '{}')",
                0,
            ).await()
            driver.execute(
                null,
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                    "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                    "remote_date_upload, remote_memo) VALUES ($chapterId, $mangaId, '/$chapterId', '1', NULL, 0, " +
                    "0, 0, 1.0, 1, 0, 0, '{}')",
                0,
            ).await()
        }
        val group = if (merged) longArrayOf(MANGA_ID, SIBLING_ID) else longArrayOf(MANGA_ID)

        val store = ViewModelStore()
        try {
            val model = model(mangas, chapters, group, progress).also { store.put("manga", it) }
            block(model, chapters)
        } finally {
            store.clear()
            advanceUntilIdle()
            driver.close()
        }
    }

    private fun model(
        mangas: MangaRepositoryImpl,
        chapters: ChapterRepository,
        group: LongArray,
        progress: Flow<Download>,
    ): MangaViewModel {
        val prefs = InMemoryPreferenceStore()
        return MangaViewModel(
            context = mockk<Context>(relaxed = true),
            mangaId = MANGA_ID,
            isFromSource = false,
            libraryPreferences = LibraryPreferences(prefs),
            trackPreferences = TrackPreferences(prefs),
            readerPreferences = ReaderPreferences(prefs),
            trackerManager = mockk<TrackerManager>(relaxed = true) {
                every { loggedInTrackersFlow() } returns flowOf(emptyList())
            },
            trackChapter = mockk(relaxed = true),
            refreshTracks = mockk(relaxed = true),
            downloadManager = mockk<DownloadManager>(relaxed = true) {
                every { queueState } returns MutableStateFlow(listOf(siblingDownload))
                every { statusFlow() } returns emptyFlow()
                every { progressFlow() } returns progress
                every { getQueuedDownloadOrNull(any()) } returns null
                every { getQueuedDownloadsByChapterId() } returns emptyMap()
                every { getDownloadedChapterIds(any(), any()) } returns emptySet()
                every { isChapterDownloaded(any(), any(), any(), any(), any()) } returns false
                every { getDownloadCount(any()) } returns 0
            },
            downloadCache = mockk<DownloadCache>(relaxed = true) { every { changes } returns MutableSharedFlow() },
            getMangaAndChapters = GetMangaWithChapters(mangas, chapters),
            getAvailableScanlators = GetAvailableScanlators(chapters),
            getExcludedScanlators = GetExcludedScanlators(mangas),
            setExcludedScanlators = mockk(relaxed = true),
            setMangaChapterFlags = mockk(relaxed = true),
            setMangaDefaultChapterFlags = mockk(relaxed = true),
            setReadStatus = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            clearCustomCover = mockk(relaxed = true),
            getTracksInGroup = mockk(relaxed = true),
            filterChaptersForDownload = mockk(relaxed = true),
            updateMangaFromRemote = mockk(relaxed = true),
            mergeManager = mockk<MangaMergeManager> {
                coEvery { computeRelatedIds(any()) } returns group
                every { relatedIdsChanges() } returns flowOf(Unit)
            },
            mangaLibraryAdder = mockk(relaxed = true),
            removeMangaFromLibrary = mockk(relaxed = true),
            mergedChapterProvider = mockk<MergedChapterProvider> {
                coEvery { stitchOf(any()) } returns emptyList()
                every { merged(any(), any()) } answers { firstArg() }
            },
            mangaPreferences = MangaPreferences(prefs),
            relatedMangasLoader = mockk(relaxed = true),
            recommendationPreferences = ReikaiRecommendationPreferences(prefs),
            relatedMangaCache = mockk(relaxed = true),
            refreshTrackerLibrary = mockk(relaxed = true),
            prepareRecommendationAssembly = mockk(relaxed = true),
            networkToLocalManga = mockk(relaxed = true),
            uiPreferences = UiPreferences(prefs),
            getFlatMetadataById = mockk<GetFlatMetadataById> {
                every { subscribe(any()) } returns flowOf(null)
                coEvery { await(any()) } returns null
            },
            getPagePreviews = mockk(relaxed = true),
            getCustomMangaInfo = mockk<GetCustomMangaInfo> { every { subscribe(any()) } returns flowOf(null) },
            setCustomMangaInfo = mockk(relaxed = true),
            sourceManager = mockk<SourceManager>(relaxed = true),
            exhPreferences = mockk(relaxed = true),
            updateHelper = mockk(relaxed = true),
            trackPorts = mockk<EntryTrackPorts> {
                every { of(any()) } returns mockk<EntryTrackPort>(relaxed = true) {
                    every { tracks() } returns flowOf(emptyList())
                }
            },
            autoBindTrackers = mockk(relaxed = true),
            remoteFirstRemoval = mockk(relaxed = true),
            editChapterNumber = mockk(relaxed = true),
        )
    }

    /** Counts live collectors of [watched]'s chapter flow, the query the details list holds open. */
    private class CountingChapters(
        private val delegate: ChapterRepository,
        private val watched: Long,
    ) : ChapterRepository by delegate {
        val active = AtomicInteger()

        override suspend fun getChapterByMangaIdAsFlow(
            mangaId: Long,
            applyScanlatorFilter: Boolean,
        ): Flow<List<Chapter>> {
            val flow = delegate.getChapterByMangaIdAsFlow(mangaId, applyScanlatorFilter)
            if (mangaId != watched) return flow
            return flow.onStart { active.incrementAndGet() }.onCompletion { active.decrementAndGet() }
        }
    }

    private companion object {
        const val MANGA_ID = 1L
        const val SIBLING_ID = 2L
        const val SIBLING_CHAPTER = 20L
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
    }
}
