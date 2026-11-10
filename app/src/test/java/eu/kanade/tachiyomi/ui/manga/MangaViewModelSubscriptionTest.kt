package eu.kanade.tachiyomi.ui.manga

import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterUnit
import reikai.util.mockIoDispatcher
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

/**
 * A merged series' details list follows every member's live downloads, progress outside the state. The subscription window itself is
 * DetailsStateSubscriptionConformanceTest's. The model hard-codes Dispatchers.IO and Default, so both are
 * pointed at the test dispatcher to keep time virtual, Default only once the helper's mocks are built.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MangaViewModelSubscriptionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockIoDispatcher(dispatcher)
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
    fun `a sibling source's download progress shows on a merged series' All view`() = runTest(dispatcher) {
        val progress = MutableSharedFlow<Download>(extraBufferCapacity = 1)
        withModel(progress = progress) { model ->
            backgroundScope.launch { model.state.collect {} }
            advanceUntilIdle()

            progress.tryEmit(siblingDownload)
            advanceUntilIdle()

            model.getDownloadProgress(SIBLING_CHAPTER) shouldBe 40
        }
    }

    @Test
    fun `a progress tick emits no new details state`() = runTest(dispatcher) {
        val progress = MutableSharedFlow<Download>(extraBufferCapacity = 1)
        withModel(progress = progress) { model ->
            backgroundScope.launch { model.state.collect {} }
            advanceUntilIdle()
            val before = model.state.value

            progress.tryEmit(siblingDownload)
            advanceUntilIdle()

            model.state.value shouldBeSameInstanceAs before
        }
    }

    @Test
    fun `a row whose download fetches a sibling's copy reads that copy's progress`() = runTest(dispatcher) {
        val progress = MutableSharedFlow<Download>(extraBufferCapacity = 1)
        // The opened series' source is gone, so its row downloads the sibling's copy of the same chapter.
        val stitch = listOf(ChapterUnit(OWN_CHAPTER, unit = 0, copyOrder = 0), ChapterUnit(SIBLING_CHAPTER, 0, 1))
        withModel(progress = progress, stitch = stitch, ownSource = GONE_SOURCE) { model ->
            backgroundScope.launch { model.state.collect {} }
            advanceUntilIdle()

            progress.tryEmit(siblingDownload)
            advanceUntilIdle()

            model.getDownloadProgress(OWN_CHAPTER) shouldBe 40
        }
    }

    private val siblingDownload = mockk<Download> {
        every { manga } returns Manga.create().copy(id = SIBLING_ID, source = 1L)
        every { chapter } returns Chapter.create().copy(id = SIBLING_CHAPTER, mangaId = SIBLING_ID)
        every { status } returns Download.State.DOWNLOADING
        every { progress } returns 40
    }

    /** A merged series of [MANGA_ID] and [SIBLING_ID], opened through the first. */
    private suspend fun TestScope.withModel(
        progress: Flow<Download>,
        stitch: List<ChapterUnit> = emptyList(),
        ownSource: Long = 1L,
        block: suspend TestScope.(MangaViewModel) -> Unit,
    ) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        driver.seedManga(MANGA_ID, chapterId = OWN_CHAPTER, sourceId = ownSource)
        driver.seedManga(SIBLING_ID, SIBLING_CHAPTER)

        val store = ViewModelStore()
        try {
            val downloads = detailsDownloadManager(queue = listOf(siblingDownload), progress = progress)
            val sources = mockk<SourceManager>(relaxed = true) {
                coEvery { getOrStub(any()) } answers {
                    val sourceId = firstArg<Long>()
                    if (sourceId == GONE_SOURCE) StubSource(sourceId, "en", "gone") else httpSource(sourceId)
                }
            }
            val model = mangaDetailsModel(
                MANGA_ID,
                MangaRepositoryImpl(database),
                ChapterRepositoryImpl(database),
                longArrayOf(MANGA_ID, SIBLING_ID),
                downloads,
                sourceManager = sources,
                stitch = stitch,
            ) {
                every { Dispatchers.Default } returns dispatcher
            }.also { store.put("manga", it) }
            block(model)
        } finally {
            store.clear()
            advanceUntilIdle()
            driver.close()
        }
    }

    private fun httpSource(sourceId: Long) = mockk<HttpSource>(relaxed = true) {
        every { id } returns sourceId
    }

    private companion object {
        const val MANGA_ID = 1L
        const val OWN_CHAPTER = 10L
        const val GONE_SOURCE = 7L
        const val SIBLING_ID = 2L
        const val SIBLING_CHAPTER = 20L
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
    }
}
