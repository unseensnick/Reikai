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
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.source.models.RemoteMangaUpdate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.download.SourceContacts
import reikai.domain.library.ContentType
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import reikai.presentation.novel.details.NovelDetailsState
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Which entry a merged series' pull-to-refresh judges each new chapter against for Download new chapters,
 * through each type's real details model: every member's new chapters against that member, so its own
 * library state, categories and read numbers decide, never the anchor's for all of them.
 */
class MergedNewChapterDownloadConformanceTest {

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

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `each member's new chapters are judged against that member`(type: ContentType) = runTest {
        val judged = when (type) {
            ContentType.MANGA -> mangaJudged()
            else -> novelJudged()
        }

        judged shouldBe mapOf(Member.ANCHOR to setOf(Member.ANCHOR), Member.SIBLING to setOf(Member.SIBLING))
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a refresh counts as reaching each member's source`(type: ContentType) = runTest {
        val contacts = SourceContacts { 0L }
        val sources = when (type) {
            ContentType.MANGA -> mangaJudged(contacts).let { listOf("1", "2") }
            else -> novelJudged(contacts).let { listOf("alpha", "beta") }
        }

        sources.map { contacts.waitBefore(it, floorMs = 1_000L) } shouldBe listOf(1_000L, 1_000L)
    }

    private enum class Member { ANCHOR, SIBLING }

    /** Entry the filter was asked about, to the entries owning the chapters it was handed, once both arrive. */
    private suspend fun judgedOnce(calls: Map<Long, Set<Long>>, roleOf: (Long) -> Member): Map<Member, Set<Member>> =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(10_000) { while (calls.values.flatten().toSet().size < 2) delay(20) }
            // Anything else a wrong rule would ask lands in the same pass.
            delay(200)
            calls.entries.associate { (owner, chapters) -> roleOf(owner) to chapters.mapTo(HashSet(), roleOf) }
        }

    private suspend fun TestScope.mangaJudged(
        contacts: SourceContacts = SourceContacts { 0L },
    ): Map<Member, Set<Member>> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        driver.seedManga(ANCHOR, chapterId = 101L, sourceId = 1L)
        driver.seedManga(SIBLING, chapterId = 201L, sourceId = 2L)
        val sources = listOf(1L, 2L).associateWith { id ->
            mockk<Source>(relaxed = true) {
                every { this@mockk.id } returns
                    id
            }
        }
        val sourceManager = mockk<SourceManager>(relaxed = true)
        coEvery { sourceManager.get(any()) } answers { sources[firstArg()] }
        coEvery { sourceManager.getOrStub(any()) } answers { sources[firstArg()] ?: mockk(relaxed = true) }
        val calls = ConcurrentHashMap<Long, Set<Long>>()
        val update = mockk<UpdateMangaFromRemote> {
            coEvery { this@mockk.invoke(any<Source>(), any(), any(), any(), any(), any()) } answers {
                val manga = secondArg<Manga>()
                Result.success(
                    RemoteMangaUpdate(manga, listOf(Chapter.create().copy(id = 900L + manga.id, mangaId = manga.id))),
                )
            }
        }
        val filter = mockk<FilterChaptersForDownload> {
            coEvery { await(any(), any()) } answers {
                calls[firstArg<Manga>().id] = secondArg<List<Chapter>>().mapTo(HashSet()) { it.mangaId }
                emptyList()
            }
        }
        val store = ViewModelStore()
        val model = mangaDetailsModel(
            ANCHOR,
            MangaRepositoryImpl(database),
            ChapterRepositoryImpl(database),
            longArrayOf(ANCHOR, SIBLING),
            sourceManager = sourceManager,
            filterChaptersForDownload = filter,
            updateMangaFromRemote = update,
            sourceContacts = contacts,
        ).also { store.put("manga", it) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(30_000) {
                    model.state.filterIsInstance<MangaViewModel.State.Success>().first { it.mergeSources.size == 2 }
                }
            }
            model.fetchAllFromSource(manualFetch = true)
            return judgedOnce(calls) { if (it == ANCHOR) Member.ANCHOR else Member.SIBLING }
        } finally {
            // The model reads on the real IO dispatcher, so it stops before the database closes.
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            job.join()
            driver.close()
        }
    }

    private suspend fun TestScope.novelJudged(
        contacts: SourceContacts = SourceContacts { 0L },
    ): Map<Member, Set<Member>> =
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.sourceContacts = contacts
            val alpha = harness.source("alpha")
            val beta = harness.source("beta")
            val anchor = harness.novel(alpha)
            val sibling = harness.novel(beta)
            harness.chapter(anchor, 1.0)
            harness.chapter(sibling, 1.0)
            harness.merge(anchor, sibling)
            alpha.details = { listingOf("alpha", anchor) }
            beta.details = { listingOf("beta", sibling) }
            val calls = ConcurrentHashMap<Long, Set<Long>>()
            harness.filterChaptersForDownload = mockk {
                coEvery { await(any(), any()) } answers {
                    calls[firstArg<Novel>().id] = secondArg<List<NovelChapter>>().mapTo(HashSet()) { it.novelId }
                    emptyList()
                }
            }
            val model = harness.openDetails(anchor)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    model.state.filterIsInstance<NovelDetailsState.Loaded>()
                        .first { it.mergeSources.size == 2 && it.sourceName == "alpha" }
                }
            }
            model.refresh()
            judgedOnce(calls) { if (it == anchor) Member.ANCHOR else Member.SIBLING }
        }

    /** [novelId]'s stored first chapter plus one new one, as [sourceId] lists it. */
    private fun listingOf(sourceId: String, novelId: Long) = SourceNovel(
        path = "/novel/$sourceId/Novel",
        name = "Novel",
        chapters = listOf(
            ChapterItem(name = "Chapter 1.0", path = "/chapter/$novelId/1.0"),
            ChapterItem(name = "Chapter 2", path = "/new/$novelId"),
        ),
    )

    private companion object {
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        const val ANCHOR = 1L
        const val SIBLING = 2L
    }
}
