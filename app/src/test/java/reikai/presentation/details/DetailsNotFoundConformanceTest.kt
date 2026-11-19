package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.ui.manga.mangaDetailsModel
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl

/**
 * A details page opened on an id with no stored row (a deleted entry, a stale notification) reaches the
 * shared not-found page, through each type's real details model and adapter. Pinned once over both types.
 */
class DetailsNotFoundConformanceTest {

    interface Probe {
        /** The neutral state the page settles on, opened on an id no row has. */
        suspend fun settledOnMissing(scope: TestScope): EntryDetailsScreenState?
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
    fun `an id with no stored row shows the not-found page`(probe: Probe) = runTest {
        probe.settledOnMissing(this) shouldBe EntryDetailsScreenState.NotFound
    }

    companion object {
        const val MISSING_ID = 404L

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        suspend fun EntryDetailsBehavior.settled(): EntryDetailsScreenState? = withContext(Dispatchers.Default) {
            withTimeoutOrNull(10_000) { state.first { it != EntryDetailsScreenState.Loading } }
        }
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun settledOnMissing(scope: TestScope): EntryDetailsScreenState? {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                MISSING_ID,
                MangaRepositoryImpl(database),
                ChapterRepositoryImpl(database),
                longArrayOf(MISSING_ID),
            ).also { store.put("manga", it) }
            return try {
                MangaEntryAdapter(model, mockk(relaxed = true), noDuplicateMarking(), noVolumePicking()).settled()
            } finally {
                // The model reads on the real IO dispatcher, so it stops before the database closes.
                val job = model.viewModelScope.coroutineContext.job
                store.clear()
                job.join()
                driver.close()
            }
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun settledOnMissing(scope: TestScope): EntryDetailsScreenState? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                NovelEntryAdapter(
                    harness.openDetails(MISSING_ID),
                    mockk(relaxed = true),
                    noDuplicateMarking(),
                    noVolumePicking(),
                )
                    .settled()
            }
    }
}
