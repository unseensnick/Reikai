package reikai.novel.source.ireader

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import ireader.core.source.HttpSource
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.Listing
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.MangasPageInfo
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import reikai.novel.source.NovelListing

class IReaderNovelSourceTest {

    private val first = object : Listing("Latest") {}
    private val second = object : Listing("Popular") {}

    private fun source(listings: List<Listing> = listOf(first)) = mockk<HttpSource> {
        every { id } returns 3L
        every { name } returns "FreeWebNovel"
        every { lang } returns "en"
        every { baseUrl } returns "https://freewebnovel.com"
        every { getFilters() } returns emptyList()
        every { getListings() } returns listings
        coEvery { getMangaList(first, 1) } returns MangasPageInfo(listOf(MangaInfo(key = "k1", title = "First")), false)
        coEvery { getMangaList(second, 1) } returns
            MangasPageInfo(listOf(MangaInfo(key = "k2", title = "Second")), false)
        coEvery { getMangaDetails(any(), any()) } returns
            MangaInfo(key = "", title = "Novel", status = MangaInfo.COMPLETED)
        coEvery { getChapterList(any(), any()) } returns listOf(
            ChapterInfo(key = "c1", name = "Chapter 1", dateUpload = 1_700_000_000_000L, number = 1f),
            ChapterInfo(key = "c2", name = "Chapter 2"),
        )
    }

    private fun adapter(source: HttpSource = source()) = IReaderNovelSource(source, app())

    @Test
    fun `popular is the source's first listing`() = runTest {
        adapter().browse(NovelListing.Popular, 1, null).items.single().name shouldBe "First"
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
}
