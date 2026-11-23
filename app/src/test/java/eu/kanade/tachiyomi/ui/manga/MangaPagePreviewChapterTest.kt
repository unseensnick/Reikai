package eu.kanade.tachiyomi.ui.manga

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/** A gallery's page previews open its own first chapter, as its More previews page does, on a merged series too. */
class MangaPagePreviewChapterTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter(any()) } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        resetVirtualDispatchers()
    }

    @Test
    fun `a merged series' preview opens the anchor's first chapter, not the one heading the merged list`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        driver.seedManga(ANCHOR, chapterId = ANCHOR_CHAPTER, sourceId = 7L)
        driver.seedManga(SIBLING, chapterId = SIBLING_CHAPTER, sourceId = 8L)
        // The sibling's chapter heads the merged list by source order.
        driver.execute(null, "UPDATE chapter SET remote_order = 0 WHERE id = $SIBLING_CHAPTER", 0).await()
        val store = ViewModelStore()
        val model = mangaDetailsModel(
            ANCHOR,
            MangaRepositoryImpl(database),
            ChapterRepositoryImpl(database),
            longArrayOf(ANCHOR, SIBLING),
        ).also { store.put("manga", it) }
        val opened = try {
            withTimeout(30_000) {
                model.state.filterIsInstance<MangaViewModel.State.Success>().first { it.chapters.size == 2 }
            }
            model.pagePreviewChapter()?.id
        } finally {
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            job.join()
            driver.close()
        }

        opened shouldBe ANCHOR_CHAPTER
    }

    private companion object {
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        const val ANCHOR = 1L
        const val SIBLING = 2L
        const val ANCHOR_CHAPTER = 10L
        const val SIBLING_CHAPTER = 20L
    }
}
