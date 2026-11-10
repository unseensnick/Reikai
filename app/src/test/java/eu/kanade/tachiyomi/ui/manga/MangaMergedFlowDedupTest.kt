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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.atomic.AtomicInteger

/**
 * SQLite tells a query only that its table was written, so a write to any manga re-runs every merged
 * member's query. The merged details list renders again only when a member's value changed: the manga
 * half of the rule NovelFlowDedupTest pins for the novel repositories.
 */
class MangaMergedFlowDedupTest {

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
    fun `a write to a manga outside the group renders the merged list no further`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        driver.seedManga(ANCHOR, chapterId = 10L)
        driver.seedManga(SIBLING, chapterId = 20L)
        driver.seedManga(OUTSIDE, chapterId = OUTSIDE_CHAPTER)
        val renders = AtomicInteger()
        val store = ViewModelStore()
        val model = mangaDetailsModel(
            ANCHOR,
            MangaRepositoryImpl(database),
            ChapterRepositoryImpl(database),
            longArrayOf(ANCHOR, SIBLING),
            onMergedRender = { renders.incrementAndGet() },
        ).also { store.put("manga", it) }
        val added = try {
            withContext(Dispatchers.Default) {
                backgroundScope.launch(Dispatchers.Default) { model.state.collect {} }
                withTimeout(30_000) {
                    model.state.filterIsInstance<MangaViewModel.State.Success>().first { it.mergeSources.size == 2 }
                }
                // Real dispatchers: the initial renders settle first.
                delay(SETTLE_MS)
                val before = renders.get()
                ChapterRepositoryImpl(database).update(ChapterUpdate(OUTSIDE_CHAPTER) { read = true })
                delay(SETTLE_MS)
                renders.get() - before
            }
        } finally {
            // The model reads on the real IO dispatcher, so it stops before the database closes.
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            job.join()
            driver.close()
        }

        added shouldBe 0
    }

    private companion object {
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        const val ANCHOR = 1L
        const val SIBLING = 2L
        const val OUTSIDE = 3L
        const val OUTSIDE_CHAPTER = 30L

        // Far beyond the milliseconds a re-render takes to land when a write causes one.
        const val SETTLE_MS = 1_000L
    }
}
