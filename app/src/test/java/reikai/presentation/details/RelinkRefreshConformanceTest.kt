package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.source.models.RemoteMangaUpdate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.local.LocalSeriesRelink
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import reikai.presentation.novel.details.NovelDetailsState
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/**
 * A local series linked to its renamed folder refreshes there as a pull to refresh does, spinner and all. Each
 * details model hands LocalSeriesRelink its own refresh, so the rule is pinned over both.
 */
class RelinkRefreshConformanceTest {

    interface Probe {
        /** Links the series to a renamed folder while the refresh it starts is still running: whether it spins. */
        suspend fun refreshingAfterRelink(scope: TestScope): Boolean
    }

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
        mockkStatic("eu.kanade.domain.manga.model.MangaKt")
        every { any<Manga>().downloadedFilter(any()) } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        resetVirtualDispatchers()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the refresh after a relink shows as refreshing`(probe: Probe) = runTest {
        probe.refreshingAfterRelink(this) shouldBe true
    }

    companion object {
        private const val WAIT_MS = 10_000L

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        // Runs the refresh it is handed, as a relink that moved the series does.
        private fun relinkThatMoves() = mockk<LocalSeriesRelink> {
            coEvery { relinkThenRefresh(any(), any(), any(), any(), any()) } coAnswers {
                arg<suspend () -> Unit>(3).invoke()
            }
        }
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun refreshingAfterRelink(scope: TestScope): Boolean {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            driver.seedManga(MANGA, chapterId = 101L)
            val release = CompletableDeferred<Unit>()
            val update = mockk<UpdateMangaFromRemote> {
                coEvery { this@mockk.invoke(any<Source>(), any(), any(), any(), any(), any()) } coAnswers {
                    release.await()
                    Result.success(RemoteMangaUpdate(secondArg(), emptyList()))
                }
            }
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                MANGA,
                MangaRepositoryImpl(database),
                ChapterRepositoryImpl(database),
                longArrayOf(MANGA),
                updateMangaFromRemote = update,
                localSeriesRelink = relinkThatMoves(),
            ).also { store.put("manga", it) }
            return try {
                val success = model.state.filterIsInstance<MangaViewModel.State.Success>()
                withTimeout(30_000) { success.first { !it.isRefreshingData } }
                model.relinkSeries("Renamed")
                // A wait that ends empty reads as false, so a missing spinner fails on the assertion.
                withTimeoutOrNull(WAIT_MS) { success.first { it.isRefreshingData } } != null
            } finally {
                release.complete(Unit)
                val job = model.viewModelScope.coroutineContext.job
                store.clear()
                job.join()
                driver.close()
            }
        }

        private companion object {
            const val MANGA = 1L
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun refreshingAfterRelink(scope: TestScope): Boolean =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val release = CompletableDeferred<Unit>()
                val source = harness.source("alpha")
                source.details = {
                    release.await()
                    SourceNovel(path = "/novel", chapters = listOf(ChapterItem("One", "/1")))
                }
                val novel = harness.novel(source)
                harness.chapter(novel, 1.0)
                harness.localSeriesRelink = relinkThatMoves()
                val model = harness.openDetails(novel)
                try {
                    val loaded = model.state.filterIsInstance<NovelDetailsState.Loaded>()
                    withTimeout(WAIT_MS) { loaded.first { !it.isRefreshing } }
                    model.relinkSeries("Renamed")
                    withTimeoutOrNull(WAIT_MS) { loaded.first { it.isRefreshing } } != null
                } finally {
                    release.complete(Unit)
                }
            }
    }
}
