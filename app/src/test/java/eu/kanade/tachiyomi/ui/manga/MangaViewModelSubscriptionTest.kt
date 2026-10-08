package eu.kanade.tachiyomi.ui.manga

import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.model.Download
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
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
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
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
        driver.seedManga(MANGA_ID, chapterId = 10L)
        driver.seedManga(SIBLING_ID, SIBLING_CHAPTER)
        val group = if (merged) longArrayOf(MANGA_ID, SIBLING_ID) else longArrayOf(MANGA_ID)

        val store = ViewModelStore()
        try {
            val downloads = detailsDownloadManager(queue = listOf(siblingDownload), progress = progress)
            val model = mangaDetailsModel(MANGA_ID, mangas, chapters, group, downloads).also { store.put("manga", it) }
            block(model, chapters)
        } finally {
            store.clear()
            advanceUntilIdle()
            driver.close()
        }
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
