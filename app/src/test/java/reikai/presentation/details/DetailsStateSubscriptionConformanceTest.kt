package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import eu.kanade.tachiyomi.ui.manga.mangaDetailsModel
import eu.kanade.tachiyomi.ui.manga.seedManga
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.novel.details.NovelDetailsState
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.mockIoDispatcher
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Each details model keeps running while its screen is hidden, but holds database updates back from its
 * state once nothing has collected it for five seconds, and delivers them when collected again (upstream's
 * SubscriberGate). One rule, pinned once over both types, by marking the opened entry's one chapter read.
 * The models hard-code Dispatchers.IO (and manga Default), so both point at the test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsStateSubscriptionConformanceTest {

    /** A details model opened on one stored entry with one unread chapter and nothing collecting its state. */
    interface Probe {
        suspend fun opened(scope: TestScope, dispatcher: TestDispatcher, block: suspend (Opened) -> Unit)
    }

    class Opened(
        val state: StateFlow<*>,
        val markRead: suspend () -> Unit,
        val shownRead: () -> Boolean,
        val activeQueries: () -> Int,
    )

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the chapter query stays open after the last collector leaves`(probe: Probe) = runTest(dispatcher) {
        probe.opened(this, dispatcher) { opened ->
            collectThenLeave(opened, 60.seconds.inWholeMilliseconds)

            opened.activeQueries() shouldBe 1
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a write made five seconds after the last collector left is held back`(probe: Probe) =
        runTest(dispatcher) {
            probe.opened(this, dispatcher) { opened ->
                collectThenLeave(opened, 5.seconds.inWholeMilliseconds)

                opened.markRead()
                advanceUntilIdle()

                opened.shownRead() shouldBe false
            }
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a held write reaches the state once it is collected again`(probe: Probe) = runTest(dispatcher) {
        probe.opened(this, dispatcher) { opened ->
            collectThenLeave(opened, 5.seconds.inWholeMilliseconds)
            opened.markRead()
            advanceUntilIdle()

            // advanceUntilIdle alone would not start a background collector with nothing else queued
            backgroundScope.launch { opened.state.collect {} }
            runCurrent()
            advanceUntilIdle()

            opened.shownRead() shouldBe true
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a write just under five seconds after the last collector left still reaches the state`(probe: Probe) =
        runTest(dispatcher) {
            probe.opened(this, dispatcher) { opened ->
                collectThenLeave(opened, 4.9.seconds.inWholeMilliseconds)

                opened.markRead()
                runCurrent()

                opened.shownRead() shouldBe true
            }
        }

    /** Collects the state until it has loaded, stops, then lets [afterMs] of virtual time pass. */
    private suspend fun TestScope.collectThenLeave(opened: Opened, afterMs: Long) {
        val collector: Job = launch { opened.state.collect {} }
        advanceUntilIdle()
        collector.cancel()
        advanceTimeBy(afterMs)
        runCurrent()
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun opened(scope: TestScope, dispatcher: TestDispatcher, block: suspend (Opened) -> Unit) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            val chapters = CountingChapters(ChapterRepositoryImpl(database), watched = MANGA_ID)
            driver.seedManga(MANGA_ID, chapterId = CHAPTER_ID)
            val store = ViewModelStore()
            try {
                val model = mangaDetailsModel(
                    MANGA_ID,
                    MangaRepositoryImpl(database),
                    chapters,
                    longArrayOf(MANGA_ID),
                ) {
                    every { Dispatchers.Default } returns dispatcher
                }.also { store.put("manga", it) }
                block(
                    Opened(
                        model.state,
                        markRead = { chapters.update(ChapterUpdate(CHAPTER_ID) { read = true }) },
                        shownRead = {
                            (model.state.value as MangaViewModel.State.Success).chapters.single().chapter.read
                        },
                        activeQueries = chapters.active::get,
                    ),
                )
            } finally {
                store.clear()
                scope.advanceUntilIdle()
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
            const val CHAPTER_ID = 10L
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun opened(scope: TestScope, dispatcher: TestDispatcher, block: suspend (Opened) -> Unit) =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                val chapter = harness.chapter(novel, 1.0)
                val model = harness.openDetails(novel, shown = false)
                block(
                    Opened(
                        model.state,
                        markRead = { harness.markRead(chapter) },
                        shownRead = {
                            val loaded = model.state.value as NovelDetailsState.Loaded
                            val row = loaded.chapters.single()
                            loaded.marks.isRead(row.id, row.read)
                        },
                        activeQueries = harness.chapterQueries::get,
                    ),
                )
            }
    }
}
