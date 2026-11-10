package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.ui.manga.mangaDetailsModel
import eu.kanade.tachiyomi.ui.manga.seedManga
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.RemoveMangaFromLibrary
import reikai.domain.novel.interactor.RemoveNovelsFromLibrary
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/**
 * A remove from library started on a details page finishes when the page closes mid-write, as the library
 * screen's does: the removal is several writes (tracker hand-out, favourite, source account, covers), and a
 * cut between them leaves a member half removed. Pinned once over both types.
 */
class DetailsRemovalScopeConformanceTest {

    interface Probe {
        /** Starts a remove, closes the page while the removal is still writing, and says whether it finished. */
        suspend fun removalFinishesAfterClose(scope: TestScope): Boolean
    }

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a remove from library survives the page closing mid-write`(probe: Probe) = runTest {
        probe.removalFinishesAfterClose(this) shouldBe true
    }

    /** A removal that holds at [gate] once [started], and completes [finished] once let through. */
    private class HeldRemoval {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        suspend fun run(ids: List<Long>): List<Long> {
            started.complete(Unit)
            gate.await()
            finished.complete(Unit)
            return ids
        }

        /** Waits for the write to start, cancels the page's scope, lets the write go, then waits for it. */
        suspend fun closeMidWrite(cancelPage: () -> Unit): Boolean = withContext(Dispatchers.Default) {
            withTimeoutOrNull(WAIT_MS) { started.await() } ?: return@withContext false
            cancelPage()
            gate.complete(Unit)
            withTimeoutOrNull(WAIT_MS) { finished.await() } != null
        }
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        private const val WAIT_MS = 5_000L

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun removalFinishesAfterClose(scope: TestScope): Boolean {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            driver.seedManga(MANGA_ID, chapterId = 10L)
            val held = HeldRemoval()
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                MANGA_ID,
                MangaRepositoryImpl(database),
                ChapterRepositoryImpl(database),
                longArrayOf(MANGA_ID),
                removeMangaFromLibrary = mockk<RemoveMangaFromLibrary> {
                    coEvery { await(any()) } coAnswers { held.run(firstArg()) }
                },
            ).also { store.put("manga", it) }
            return try {
                model.removeFromLibrary(listOf(MANGA_ID))
                held.closeMidWrite { model.viewModelScope.cancel() }
            } finally {
                held.gate.complete(Unit)
                val job = model.viewModelScope.coroutineContext.job
                store.clear()
                job.join()
                driver.close()
            }
        }

        private companion object {
            const val MANGA_ID = 1L
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun removalFinishesAfterClose(scope: TestScope): Boolean =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val held = HeldRemoval()
                harness.removeNovelsFromLibrary = mockk<RemoveNovelsFromLibrary> {
                    coEvery { await(any()) } coAnswers { held.run(firstArg()) }
                }
                val novel = harness.novel(harness.source("alpha"))
                val model = harness.openDetails(novel, shown = false)
                try {
                    model.removeFromLibrary(listOf(novel))
                    held.closeMidWrite { model.viewModelScope.cancel() }
                } finally {
                    held.gate.complete(Unit)
                }
            }
    }
}
