package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.novel.host.ChapterItem
import reikai.novel.host.NovelItem
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager

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

    private val adapter = NovelMigrationFlowAdapter(
        sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get("plugin") } returns source },
        getEnabledNovelSources = mockk(),
        sourcePreferences = mockk(),
        novelPreferences = mockk(),
        novelRepository = mockk<NovelRepository> { coEvery { getByUrlAndSource(any(), any()) } returns null },
        chapterRepository = mockk(),
        database = mockk(),
        libraryPreferences = mockk(),
        coverCache = mockk(),
        downloadManagerProvider = { mockk() },
        migrateNovel = mockk(),
        mergeManager = mockk(),
        installer = mockk(),
    )

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
}
