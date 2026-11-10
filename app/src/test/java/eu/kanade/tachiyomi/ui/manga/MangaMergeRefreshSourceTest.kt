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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The manga details screen hands the shared merge refresh (`refreshMergeGroup`) only an installed
 * sibling source: a stub one would fail every refresh of the group. The novel screen resolves its
 * siblings from installed sources alone, so it has no stub to hand over.
 */
class MangaMergeRefreshSourceTest {

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
    fun `a sibling whose source is not installed is not refreshed`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        driver.seedManga(ANCHOR, chapterId = 101L, sourceId = INSTALLED)
        driver.seedManga(SIBLING, chapterId = 201L, sourceId = UNINSTALLED)
        val refreshed = ConcurrentHashMap.newKeySet<Long>()
        val update = mockk<UpdateMangaFromRemote> {
            coEvery { this@mockk.invoke(any<Source>(), any(), any(), any(), any(), any()) } answers {
                val manga = secondArg<Manga>()
                refreshed += manga.id
                Result.success(RemoteMangaUpdate(manga, emptyList()))
            }
        }
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            coEvery { get(UNINSTALLED) } returns null
        }
        val store = ViewModelStore()
        val model = mangaDetailsModel(
            ANCHOR,
            MangaRepositoryImpl(database),
            ChapterRepositoryImpl(database),
            longArrayOf(ANCHOR, SIBLING),
            sourceManager = sourceManager,
            updateMangaFromRemote = update,
        ).also { store.put("manga", it) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(30_000) {
                    model.state.filterIsInstance<MangaViewModel.State.Success>().first { it.mergeSources.size == 2 }
                }
                model.fetchAllFromSource(manualFetch = false)
                withTimeout(10_000) { while (ANCHOR !in refreshed) delay(20) }
                // A sibling refreshed through a stub would land in the same pass.
                delay(200)
            }

            refreshed shouldBe setOf(ANCHOR)
        } finally {
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            job.join()
            driver.close()
        }
    }

    private companion object {
        const val ANCHOR = 1L
        const val SIBLING = 2L
        const val INSTALLED = 1L
        const val UNINSTALLED = 2L
    }
}
