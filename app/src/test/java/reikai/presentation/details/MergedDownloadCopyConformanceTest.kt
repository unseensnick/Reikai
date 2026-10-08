package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.manga.detailsDownloadManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.merge.ChapterUnit
import reikai.domain.novel.model.NovelChapter
import reikai.presentation.reader.FakeNovelSource
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Which copy a merged series' All view downloads, through each type's real details model and adapter.
 * The group is a member whose source is gone (M) and an installed one (A), opened through M. A row
 * the stitch shows from M's copy fetches A's copy of that chapter, a chapter only M holds offers no
 * download, and a row shown from A fetches its own. What the list shows does not move.
 */
class MergedDownloadCopyConformanceTest {

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
    fun `a chapter both hold is still shown from the missing source's copy`(type: ContentType) = runTest {
        group(type) { it.shownIds } shouldBe setOf(Copy.M_SHARED, Copy.M_ONLY, Copy.M_LAST, Copy.A_ONLY)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `downloading a row shown from a missing source fetches the installed source's copy`(type: ContentType) =
        runTest {
            group(type) { it.download(Copy.M_SHARED, ChapterDownloadAction.START) } shouldBe setOf(Copy.A_SHARED)
        }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `starting a row shown from a missing source now fetches the installed source's copy`(type: ContentType) =
        runTest {
            group(type) { it.download(Copy.M_SHARED, ChapterDownloadAction.START_NOW) } shouldBe setOf(Copy.A_SHARED)
        }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a chapter only the missing source holds offers no download`(type: ContentType) = runTest {
        group(type) { it.offersDownload(Copy.M_ONLY) } shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a row shown from the installed source downloads its own copy`(type: ContentType) = runTest {
        group(type) { it.download(Copy.A_ONLY, ChapterDownloadAction.START) } shouldBe setOf(Copy.A_ONLY)
    }

    /** The copies the group holds, by role; each type maps them to its own ids. M holds the most, so it
     *  is the stitch's first-ranked source and its copy of the shared chapter is the one shown. */
    private enum class Copy { M_SHARED, M_ONLY, M_LAST, A_SHARED, A_ONLY }

    /** The opened All view: its settled state, its behaviour, and the copies a download handed over. */
    private class Group(
        private val behavior: EntryDetailsBehavior,
        private val loaded: EntryDetailsScreenState.Loaded,
        private val ids: Map<Copy, Long>,
        private val queued: Set<Long>,
    ) {
        private val roleOf = ids.entries.associate { (role, id) -> id to role }
        private val rows = loaded.chapters.items.filterIsInstance<EntryChapterListItem.Chapter>()

        val shownIds: Set<Copy> get() = rows.mapTo(HashSet()) { roleOf.getValue(it.id) }

        fun offersDownload(copy: Copy): Boolean =
            loaded.rowOffersDownload(ids.getValue(copy), rows.first { it.id == ids.getValue(copy) }.downloadState)

        suspend fun download(copy: Copy, action: ChapterDownloadAction): Set<Copy> {
            behavior.onChapterDownloadAction(ids.getValue(copy), action)
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(10_000) { while (queued.isEmpty()) delay(20) }
                // Anything else a wrong rule would queue lands in the same pass.
                delay(200)
            }
            return queued.mapTo(HashSet()) { roleOf.getValue(it) }
        }
    }

    private suspend fun <T> TestScope.group(type: ContentType, probe: suspend (Group) -> T): T = when (type) {
        ContentType.MANGA -> mangaGroup(probe)
        else -> novelGroup(probe)
    }

    private suspend fun <T> mangaGroup(probe: suspend (Group) -> T): T {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        val ids = mapOf(
            Copy.M_SHARED to 101L,
            Copy.M_ONLY to 102L,
            Copy.M_LAST to 104L,
            Copy.A_SHARED to 201L,
            Copy.A_ONLY to 203L,
        )
        driver.seedManga(M, chapterId = 101L, sourceId = M_SOURCE)
        driver.seedManga(A, chapterId = 201L, sourceId = A_SOURCE)
        driver.seedChapter(M, 102L)
        driver.seedChapter(M, 104L)
        driver.seedChapter(A, 203L)
        val stitch = listOf(
            ChapterUnit(chapterId = 101L, unit = 0, copyOrder = 0),
            ChapterUnit(chapterId = 201L, unit = 0, copyOrder = 1),
            ChapterUnit(chapterId = 102L, unit = 1, copyOrder = 0),
            ChapterUnit(chapterId = 203L, unit = 2, copyOrder = 0),
            ChapterUnit(chapterId = 104L, unit = 3, copyOrder = 0),
        )
        val queued = ConcurrentHashMap.newKeySet<Long>()
        val downloads = detailsDownloadManager().apply {
            coEvery { downloadChapters(any(), any(), any()) } answers
                { secondArg<List<Chapter>>().forEach { queued += it.id } }
            every { startDownloadNow(any()) } answers { queued += firstArg<Long>() }
        }
        val sources = mockk<SourceManager>(relaxed = true) {
            coEvery { getOrStub(any()) } answers {
                val sourceId = firstArg<Long>()
                if (sourceId == A_SOURCE) httpSource(sourceId) else StubSource(sourceId, "en", "gone")
            }
        }
        val store = ViewModelStore()
        val model = mangaDetailsModel(
            M,
            MangaRepositoryImpl(database),
            ChapterRepositoryImpl(database),
            longArrayOf(M, A),
            downloadManager = downloads,
            sourceManager = sources,
            stitch = stitch,
        ).also { store.put("manga", it) }
        try {
            val behavior = MangaEntryAdapter(model, mockk(relaxed = true))
            return probe(Group(behavior, behavior.settled(rows = 4), ids, queued))
        } finally {
            // The model reads on the real IO dispatcher, so it stops before the database closes.
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            job.join()
            driver.close()
        }
    }

    private suspend fun <T> TestScope.novelGroup(probe: suspend (Group) -> T): T =
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val m = harness.novel(FakeNovelSource("gone", "unregistered"))
            val a = harness.novel(harness.source("alpha"))
            // Titled, so the stitch pairs the two copies of the opening.
            val ids = mapOf(
                Copy.M_SHARED to harness.chapter(m, 1.0, name = "Opening").id,
                Copy.M_ONLY to harness.chapter(m, 2.0, name = "Only gone has this").id,
                Copy.A_SHARED to harness.chapter(a, 1.0, name = "Opening").id,
                Copy.M_LAST to harness.chapter(m, 4.0, name = "Gone has this one too").id,
                Copy.A_ONLY to harness.chapter(a, 3.0, name = "Only alpha has this").id,
            )
            harness.merge(m, a)
            val queued = ConcurrentHashMap.newKeySet<Long>()
            coEvery { harness.downloadManager.downloadChapters(any()) } answers {
                firstArg<List<NovelChapter>>().forEach { queued += it.id }
            }
            every { harness.downloadManager.startDownloadNow(any()) } answers { queued += firstArg<Long>() }
            val behavior = NovelEntryAdapter(harness.openDetails(m), mockk(relaxed = true))
            probe(Group(behavior, behavior.settled(rows = 4), ids, queued))
        }

    /** The merged page once the installed member serves it and every row of the stitch is listed. */
    private suspend fun EntryDetailsBehavior.settled(rows: Int): EntryDetailsScreenState.Loaded =
        withContext(Dispatchers.Default) {
            withTimeout(30_000) {
                state.first {
                    it is EntryDetailsScreenState.Loaded && it.isMerged && it.webPage != null &&
                        it.chapters.items.count { item -> item is EntryChapterListItem.Chapter } == rows
                } as EntryDetailsScreenState.Loaded
            }
        }

    private fun httpSource(sourceId: Long) = mockk<HttpSource>(relaxed = true) {
        every { id } returns sourceId
        every { name } returns "src$sourceId"
        every { getMangaUrl(any()) } answers { "https://$sourceId.example" + firstArg<SManga>().url }
    }

    private suspend fun JdbcSqliteDriver.seedChapter(mangaId: Long, chapterId: Long) {
        execute(
            null,
            "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                "remote_date_upload, remote_memo) VALUES ($chapterId, $mangaId, '/$chapterId', '$chapterId', " +
                "NULL, 0, 0, 0, $chapterId, $chapterId, 0, 0, '{}')",
            0,
        ).await()
    }

    private companion object {
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        const val M = 1L
        const val A = 2L
        const val M_SOURCE = 10L
        const val A_SOURCE = 11L
    }
}
