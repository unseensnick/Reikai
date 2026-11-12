package eu.kanade.tachiyomi.ui.manga

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.source.Source
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.source.models.RemoteMangaUpdate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.local.LocalSeriesRelink
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/** A local series linked to its renamed folder refreshes there as a pull to refresh does, spinner and all. */
class MangaRelinkRefreshTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic("eu.kanade.domain.manga.model.MangaKt")
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
    fun `the refresh after a relink shows as refreshing`() = runTest {
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
        val relink = mockk<LocalSeriesRelink> {
            coEvery { relinkThenRefresh(any(), any(), any(), any(), any()) } coAnswers {
                arg<suspend () -> Unit>(3).invoke()
            }
        }
        val store = ViewModelStore()
        val model = mangaDetailsModel(
            MANGA,
            MangaRepositoryImpl(database),
            ChapterRepositoryImpl(database),
            longArrayOf(MANGA),
            updateMangaFromRemote = update,
            localSeriesRelink = relink,
        ).also { store.put("manga", it) }
        try {
            val refreshing = withContext(Dispatchers.Default) {
                withTimeout(30_000) {
                    model.state.filterIsInstance<MangaViewModel.State.Success>().first { !it.isRefreshingData }
                }
                model.relinkSeries("Renamed")
                withTimeout(10_000) {
                    model.state.filterIsInstance<MangaViewModel.State.Success>().first { it.isRefreshingData }
                }.isRefreshingData
            }
            release.complete(Unit)

            refreshing shouldBe true
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
