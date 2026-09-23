package reikai.novel.source.ireader

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import ireader.core.source.HttpSource
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.Command
import ireader.core.source.model.Listing
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.MangasPageInfo
import ireader.core.source.model.Text
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelPageKind

class IReaderNovelSourceTest {

    private val first = object : Listing("Latest") {}
    private val second = object : Listing("Popular") {}

    private val detailCommands = slot<List<Command<*>>>()
    private val contentCommands = slot<List<Command<*>>>()

    private fun source(
        listings: List<Listing> = listOf(first),
        commands: List<Command<*>> = emptyList(),
    ) = mockk<HttpSource> {
        every { getCommands() } returns commands
        coEvery { getPageList(any(), capture(contentCommands)) } returns listOf(Text("Saved"))
        every { id } returns 3L
        every { name } returns "FreeWebNovel"
        every { lang } returns "en"
        every { baseUrl } returns "https://freewebnovel.com"
        every { getFilters() } returns emptyList()
        every { getListings() } returns listings
        coEvery { getMangaList(first, 1) } returns MangasPageInfo(listOf(MangaInfo(key = "k1", title = "First")), false)
        coEvery { getMangaList(second, 1) } returns
            MangasPageInfo(listOf(MangaInfo(key = "k2", title = "Second")), false)
        coEvery { getMangaDetails(any(), capture(detailCommands)) } returns
            MangaInfo(key = "", title = "Novel", status = MangaInfo.COMPLETED)
        coEvery { getChapterList(any(), any()) } returns listOf(
            ChapterInfo(key = "c1", name = "Chapter 1", dateUpload = 1_700_000_000_000L, number = 1f),
            ChapterInfo(key = "c2", name = "Chapter 2"),
        )
    }

    private fun adapter(source: HttpSource = source()) = IReaderNovelSource(source, app())

    @Test
    fun `a source declaring the fetch commands takes every kind of page`() {
        adapter(source(commands = FETCH_COMMANDS)).pageFetch?.kinds shouldBe NovelPageKind.entries.toSet()
    }

    @Test
    fun `a source declaring no commands takes no page`() {
        adapter().pageFetch.shouldBeNull()
    }

    @Test
    fun `a page for details reaches the source with its address and markup`() = runTest {
        adapter(source(commands = FETCH_COMMANDS)).pageFetch!!.details("novel/x", PAGE_URL, PAGE_HTML)
        (detailCommands.captured.single() as Command.Detail.Fetch).let { it.url to it.html } shouldBe
            (PAGE_URL to PAGE_HTML)
    }

    @Test
    fun `a page for details keeps the novel's own path`() = runTest {
        adapter(source(commands = FETCH_COMMANDS)).pageFetch!!.details("novel/x", PAGE_URL, PAGE_HTML).path shouldBe
            "novel/x"
    }

    @Test
    fun `a page for a chapter reaches the source as its content`() = runTest {
        adapter(source(commands = FETCH_COMMANDS)).pageFetch!!.chapterText("c1", PAGE_URL, PAGE_HTML)
        (contentCommands.captured.single() as Command.Content.Fetch).html shouldBe PAGE_HTML
    }

    @Test
    fun `popular is the source's first listing`() = runTest {
        adapter().browse(NovelListing.Popular, 1, null).items.single().name shouldBe "First"
    }

    @Test
    fun `a listed novel with no title is left out, as IReader leaves it out`() = runTest {
        val source = source().also {
            coEvery { it.getMangaList(first, 1) } returns
                MangasPageInfo(
                    listOf(MangaInfo(key = "k0", title = " "), MangaInfo(key = "k1", title = "First")),
                    false,
                )
        }
        adapter(source).browse(NovelListing.Popular, 1, null).items.map { it.path } shouldBe listOf("k1")
    }

    @Test
    fun `latest is its second listing`() = runTest {
        adapter(source(listOf(first, second))).browse(NovelListing.Latest, 1, null).items.single().name shouldBe
            "Second"
    }

    @Test
    fun `a source with one listing offers no separate latest`() {
        adapter().supportsLatest shouldBe false
    }

    @Test
    fun `a source with two listings offers latest`() {
        adapter(source(listOf(first, second))).supportsLatest shouldBe true
    }

    @Test
    fun `a status reads the way the other formats say it`() = runTest {
        adapter().parseNovel("k").status shouldBe "Completed"
    }

    @Test
    fun `a chapter's upload time is kept to the millisecond`() = runTest {
        adapter().parseNovel("k").chapters!!.first().releaseTime shouldBe "2023-11-14T22:13:20Z"
    }

    @Test
    fun `a chapter with no number is unnumbered rather than chapter minus one`() = runTest {
        adapter().parseNovel("k").chapters!!.last().chapterNumber shouldBe null
    }

    @Test
    fun `a relative key opens under the site`() {
        adapter().webUrl("novel/x", isNovel = true) shouldBe "https://freewebnovel.com/novel/x"
    }

    @Test
    fun `an absolute key opens as it is`() {
        adapter().webUrl("https://other.example/x", isNovel = true) shouldBe "https://other.example/x"
    }

    private fun app() = Extension.Loaded(
        name = "FreeWebNovel",
        pkgName = "ireader.freewebnovel.en",
        versionName = "2.17",
        versionCode = 17,
        libVersion = 2.0,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        isShared = true,
        kind = Extension.Kind.IREADER,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
    )

    private companion object {
        const val PAGE_URL = "https://freewebnovel.com/novel/x"
        const val PAGE_HTML = "<html><body>page</body></html>"
        val FETCH_COMMANDS = listOf(Command.Detail.Fetch(), Command.Chapter.Fetch(), Command.Content.Fetch())
    }
}
