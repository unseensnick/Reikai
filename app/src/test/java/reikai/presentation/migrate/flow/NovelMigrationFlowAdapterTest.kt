package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.host.ChapterItem
import reikai.novel.host.NovelItem
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import java.io.IOException

/**
 * The novel half of the two smart-match options. Deep search and prioritize-by-chapters were hidden
 * for novels on the premise that no novel engine existed, while the novel engine already extended
 * Mihon's generic one; these pin that both options now reach a novel search.
 */
class NovelMigrationFlowAdapterTest {

    private val source = mockk<NovelSource>(relaxed = true) {
        every { id } returns "plugin"
        // Answers only a query stripped of its bracketed tags, which only deep search sends.
        coEvery { search(any(), any(), any()) } answers {
            val query = firstArg<String>()
            val hits = if ('[' in query) emptyList() else listOf(NovelItem("Title", "/title", null))
            NovelItemsPage(hits, hasNextPage = false)
        }
        coEvery { parseNovel("/title") } returns SourceNovel(
            path = "/title",
            chapters = listOf(chapter(1.0), chapter(2.0)),
            totalPages = 2,
        )
        coEvery { parsePage("/title", "2") } returns SourceNovel(
            path = "/title",
            chapters = listOf(chapter(3.0)),
        )
    }

    private fun adapter(
        novelRepository: NovelRepository = mockk { coEvery { getByUrlAndSource(any(), any()) } returns null },
        chapterRepository: NovelChapterRepository = mockk(),
    ) = NovelMigrationFlowAdapter(
        sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get("plugin") } returns source },
        getEnabledNovelSources = mockk(),
        sourcePreferences = mockk(),
        novelPreferences = mockk(),
        novelRepository = novelRepository,
        chapterRepository = chapterRepository,
        database = mockk(),
        libraryPreferences = mockk(),
        coverCache = mockk(),
        downloadManagerProvider = { mockk() },
        migrateNovel = mockk(),
        mergeManager = mockk(),
        installer = mockk(),
    )

    private val adapter = adapter()

    private fun chapter(number: Double) = ChapterItem(
        name = "Chapter $number",
        path = "/c$number",
        chapterNumber = number,
    )

    private val entry = MigrationEntry(
        id = EntryId.Novel(1L),
        title = "[Group] Title (Web Novel)",
        sourceKey = "elsewhere",
        sourceName = null,
        chapterCount = 1,
        cover = null,
        payload = Any(),
    )

    @Test
    fun `deep search finds a title only its cleaned query matches`() = runTest {
        adapter.suggest(entry, "plugin", MigrationTuning(deepSearch = true))?.title shouldBe "Title"
    }

    @Test
    fun `prioritize-by-chapters counts every page of a paged chapter list`() = runTest {
        val tuning = MigrationTuning(deepSearch = true, prioritizeByChapters = true)

        adapter.suggest(entry, "plugin", tuning)?.chapterCount shouldBe 3
    }

    @Test
    fun `the count peek reads a hit's stored chapters before parsing the source`() = runTest {
        // The source's first page undercounts, so a parse-only peek has no count to give here.
        val stored = Novel.create().copy(id = 7L, source = "plugin", url = "/title")
        val adapter = adapter(
            novelRepository = mockk { coEvery { getByUrlAndSource("/title", "plugin") } returns stored },
            chapterRepository = mockk {
                coEvery { getByNovelId(7L) } returns listOf(1.0, 2.0, 5.0).map(::storedChapter)
            },
        )
        val candidate = MigrationCandidate(
            sourceKey = "plugin",
            title = "Title",
            chapterCount = null,
            key = "plugin:/title",
            handle = NovelCandidateHandle(NovelItem("Title", "/title", null)),
        )

        adapter.peekCounts(candidate)?.let { it.chapterCount to it.latestChapter } shouldBe (3 to 5.0)
    }

    @Test
    fun `a failed target refresh still resolves, unsynced, so the engine makes the second attempt`() = runTest {
        coEvery { source.parseNovel("/flaky") } throws IOException("timeout")
        val stored = Novel.create().copy(id = 9L, source = "plugin", url = "/flaky")
        val adapter = adapter(
            novelRepository = mockk {
                coEvery { insertOrGet(any()) } returns stored
                coEvery { getByUrlAndSource("/flaky", "plugin") } returns stored
            },
            chapterRepository = mockk { coEvery { getByNovelId(9L) } returns emptyList() },
        )

        adapter.resolve(hit("/flaky"))?.syncedNow shouldBe false
    }

    @Test
    fun `resolving a hit whose stored row has chapters does not parse the source again`() = runTest {
        val stored = Novel.create().copy(id = 7L, source = "plugin", url = "/title")
        val adapter = adapter(
            novelRepository = mockk {
                coEvery { insertOrGet(any()) } returns stored
                coEvery { getByUrlAndSource("/title", "plugin") } returns stored
            },
            chapterRepository = mockk { coEvery { getByNovelId(7L) } returns listOf(storedChapter(1.0)) },
        )

        adapter.resolve(hit("/title"))

        coVerify(exactly = 0) { source.parseNovel(any()) }
    }

    private fun hit(path: String) = MigrationCandidate(
        sourceKey = "plugin",
        title = "Title",
        chapterCount = null,
        key = "plugin:$path",
        handle = NovelCandidateHandle(NovelItem("Title", path, null)),
    )

    private fun storedChapter(number: Double) = NovelChapter(
        id = number.toLong(),
        novelId = 7L,
        url = "/c$number",
        name = "Chapter $number",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = number,
        sourceOrder = 0L,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )
}
