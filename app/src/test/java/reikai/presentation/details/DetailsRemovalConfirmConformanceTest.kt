package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.RemoveMangaFromLibrary
import reikai.domain.novel.interactor.RemoveNovelsFromLibrary
import reikai.presentation.novel.details.NovelDetailsDialog
import reikai.presentation.novel.details.NovelDetailsState
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The details heart never removes unasked, through each type's real details model: tapped on a library
 * entry it opens the confirm and removes nothing, and a confirm left open after the entry left the library
 * elsewhere removes nothing. Pinned once over both types.
 */
class DetailsRemovalConfirmConformanceTest {

    interface Probe {
        /** Taps the heart on a lone library entry: whether the confirm opened, and what was removed. */
        suspend fun heartOnLibraryEntry(scope: TestScope): Pair<Boolean, List<Long>>

        /** Confirms a remove of an entry no longer in the library, and returns what was removed. */
        suspend fun staleConfirm(scope: TestScope): List<Long>
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
    fun `the heart on a library entry asks before removing`(probe: Probe) = runTest {
        probe.heartOnLibraryEntry(this) shouldBe (true to emptyList())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a confirm left open after the entry left the library removes nothing`(probe: Probe) = runTest {
        probe.staleConfirm(this) shouldBe emptyList()
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        private const val WAIT_MS = 10_000L

        // Time for a removal launched on another dispatcher to have reached the interactor.
        private const val SETTLE_MS = 300L

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        private fun recorder(removed: MutableList<Long>) = { ids: List<Long> ->
            removed += ids
            ids
        }
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun heartOnLibraryEntry(scope: TestScope): Pair<Boolean, List<Long>> =
            withModel(favorite = true) { model, removed ->
                val success = model.state.filterIsInstance<MangaViewModel.State.Success>()
                success.first()
                model.toggleFavorite()
                success.first { it.dialog is MangaViewModel.Dialog.RemoveFromLibrary }
                delay(SETTLE_MS)
                true to removed.toList()
            }

        override suspend fun staleConfirm(scope: TestScope): List<Long> = withModel(favorite = false) {
                model,
                removed,
            ->
            model.removeFromLibrary(listOf(MANGA_ID))
            delay(SETTLE_MS)
            removed.toList()
        }

        private suspend fun <T> withModel(
            favorite: Boolean,
            block: suspend (MangaViewModel, List<Long>) -> T,
        ): T {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            driver.seedManga(MANGA_ID, chapterId = 10L)
            if (!favorite) driver.execute(null, "UPDATE manga SET user_favorite_at = NULL", 0).await()
            val removed = CopyOnWriteArrayList<Long>()
            val record = recorder(removed)
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                MANGA_ID,
                MangaRepositoryImpl(database),
                ChapterRepositoryImpl(database),
                longArrayOf(MANGA_ID),
                removeMangaFromLibrary = mockk<RemoveMangaFromLibrary> {
                    coEvery { await(any()) } coAnswers { record(firstArg()) }
                },
            ).also { store.put("manga", it) }
            return try {
                withContext(Dispatchers.Default) { withTimeout(WAIT_MS) { block(model, removed) } }
            } finally {
                // The model reads on the real IO dispatcher, so it stops before the database closes.
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

        override suspend fun heartOnLibraryEntry(scope: TestScope): Pair<Boolean, List<Long>> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val removed = CopyOnWriteArrayList<Long>()
                harness.removeNovelsFromLibrary = recording(removed)
                val model = harness.openDetails(harness.novel(harness.source("alpha")))
                withContext(Dispatchers.Default) {
                    withTimeout(WAIT_MS) {
                        model.state.first { it is NovelDetailsState.Loaded }
                        model.toggleFavorite()
                        model.state.first {
                            (it as? NovelDetailsState.Loaded)?.dialog is NovelDetailsDialog.RemoveFromLibrary
                        }
                        delay(SETTLE_MS)
                    }
                }
                true to removed.toList()
            }

        override suspend fun staleConfirm(scope: TestScope): List<Long> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val removed = CopyOnWriteArrayList<Long>()
                harness.removeNovelsFromLibrary = recording(removed)
                val novel = harness.novel(harness.source("alpha"), inLibrary = false)
                val model = harness.openDetails(novel, shown = false)
                model.removeFromLibrary(listOf(novel))
                withContext(Dispatchers.Default) { delay(SETTLE_MS) }
                removed.toList()
            }

        private fun recording(removed: MutableList<Long>): RemoveNovelsFromLibrary {
            val record = recorder(removed)
            return mockk { coEvery { await(any()) } coAnswers { record(firstArg()) } }
        }
    }
}
