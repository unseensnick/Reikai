package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
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
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.presentation.reader.FakeNovelSource
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Instant

/**
 * What a merged series' All view downloads, opens on the web and takes its update interval from, through
 * each type's real details model and adapter. Opened through a member whose source is gone, it is the
 * first installed member in the group's order; through an installed one, that member; with none
 * installed, the opened member, whose source the page reports as missing.
 */
class UnifiedViewMemberConformanceTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
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

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `opened through a missing member, All downloads through an installed one`(type: ContentType) = runTest {
        allView(type, installed = listOf(false, true, true), opened = 0).downloadable shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `opened through a missing member, All opens the first installed member's page`(type: ContentType) = runTest {
        allView(type, installed = listOf(false, true, true), opened = 0).pageOf shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `opened through a missing member, All shows the first installed member's interval`(type: ContentType) =
        runTest {
            allView(type, installed = listOf(false, true, true), opened = 0).nextUpdateOf shouldBe 1
        }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `opened through an installed member, All keeps that member's page`(type: ContentType) = runTest {
        allView(type, installed = listOf(false, true, true), opened = 2).pageOf shouldBe 2
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `opened through an installed member, All keeps that member's interval`(type: ContentType) = runTest {
        allView(type, installed = listOf(false, true, true), opened = 2).nextUpdateOf shouldBe 2
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `with every member missing, All offers no downloads`(type: ContentType) = runTest {
        allView(type, installed = listOf(false, false), opened = 0).downloadable shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `with every member missing, All has no page`(type: ContentType) = runTest {
        allView(type, installed = listOf(false, false), opened = 0).pageOf shouldBe null
    }

    /** The All view as member indices: whose page it opens and whose interval it shows. */
    private data class Shown(val downloadable: Boolean, val pageOf: Int?, val nextUpdateOf: Int?)

    private suspend fun TestScope.allView(type: ContentType, installed: List<Boolean>, opened: Int): Shown =
        when (type) {
            ContentType.MANGA -> mangaAllView(installed, opened)
            else -> novelAllView(installed, opened)
        }

    private suspend fun mangaAllView(installed: List<Boolean>, opened: Int): Shown {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        val ids = installed.indices.map { it + 1L }
        ids.forEachIndexed { i, id -> driver.seedManga(id, chapterId = 100 + id, sourceId = 10L + i, dueAt(i)) }
        val sources = mockk<SourceManager>(relaxed = true) {
            coEvery { getOrStub(any()) } answers {
                val sourceId = firstArg<Long>()
                if (installed[(sourceId - 10L).toInt()]) httpSource(sourceId) else StubSource(sourceId, "en", "gone")
            }
        }
        val store = ViewModelStore()
        val model = mangaDetailsModel(
            ids[opened],
            MangaRepositoryImpl(database),
            ChapterRepositoryImpl(database),
            ids.toLongArray(),
            sourceManager = sources,
        ).also { store.put("manga", it) }
        try {
            return MangaEntryAdapter(model, mockk(relaxed = true)).settled().shown(ids)
        } finally {
            // The model reads on the real IO dispatcher, so it stops before the database closes.
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            job.join()
            driver.close()
        }
    }

    private suspend fun TestScope.novelAllView(installed: List<Boolean>, opened: Int): Shown =
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val ids = installed.mapIndexed { i, on ->
                harness.novel(if (on) harness.source("src$i") else FakeNovelSource("gone$i", "unregistered"))
            }
            ids.forEachIndexed { i, id ->
                harness.chapter(id, 1.0)
                harness.updateNovel(id) { nextUpdate = dueAt(i) }
            }
            harness.merge(*ids.toLongArray())
            NovelEntryAdapter(harness.openDetails(ids[opened]), mockk(relaxed = true)).settled().shown(ids)
        }

    /** The merged page once its page has resolved, or once its source is known to be missing. */
    private suspend fun EntryDetailsBehavior.settled(): EntryDetailsScreenState.Loaded =
        withContext(Dispatchers.Default) {
            withTimeout(30_000) {
                state.first {
                    it is EntryDetailsScreenState.Loaded && it.isMerged &&
                        (it.webPage != null || it.details.header.sourceState == EntrySourceState.Missing)
                } as EntryDetailsScreenState.Loaded
            }
        }

    private fun EntryDetailsScreenState.Loaded.shown(ids: List<Long>) = Shown(
        downloadable = chaptersDownloadable,
        pageOf = webPage?.let { ids.indexOf(it.viewed.rawId) },
        nextUpdateOf = ids.indices.firstOrNull { Instant.fromEpochMilliseconds(dueAt(it)) == details.nextUpdate },
    )

    private fun httpSource(sourceId: Long) = mockk<HttpSource>(relaxed = true) {
        every { id } returns sourceId
        every { name } returns "src$sourceId"
        every { getMangaUrl(any()) } answers { "https://$sourceId.example" + firstArg<SManga>().url }
    }

    private companion object {
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"

        /** Each member is next due on its own day, so the interval shown names its member. */
        fun dueAt(index: Int): Long = (index + 1) * 86_400_000L
    }
}
