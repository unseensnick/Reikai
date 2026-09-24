package reikai.novel.source

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.Listing
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.MangasPageInfo
import ireader.core.source.model.Text
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.novel.host.ChapterItem
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginInfo
import reikai.novel.host.SourceNovel
import reikai.novel.source.ireader.IReaderNovelSource
import ireader.core.source.CatalogSource as IReaderCatalogSource

/**
 * What every novel source owes the app, whatever format it comes in. Each kind is fed its format's own
 * native answer (a plugin and an IReader source list chapters oldest first, a tachiyomi source newest
 * first) and must hand the app the same result.
 */
class NovelSourceConformanceTest {

    enum class Kind { PLUGIN, APP, IREADER }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a novel's chapters come in reading order`(kind: Kind) = runTest {
        source(kind).parseNovel("novel").chapters.orEmpty().map { it.path } shouldBe listOf("c1", "c2")
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a chapter's control characters are stripped`(kind: Kind) = runTest {
        source(kind).parseChapter("c1") shouldBe "<p>ab</p>"
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `the last page of a listing says so`(kind: Kind) = runTest {
        source(kind).browse(NovelListing.Popular, page = 2, filters = null).hasNextPage shouldBe false
    }

    /** Owed by the adapters that convert a native chapter; a plugin hands its own fields over as they are. */
    @ParameterizedTest
    @EnumSource(Kind::class, names = ["APP", "IREADER"])
    fun `a chapter's upload date is kept to the millisecond`(kind: Kind) = runTest {
        source(kind).parseNovel("novel").chapters.orEmpty().first().releaseTime shouldBe "2023-11-14T22:13:20.123Z"
    }

    @ParameterizedTest
    @EnumSource(Kind::class, names = ["APP", "IREADER"])
    fun `a chapter with no upload date has none`(kind: Kind) = runTest {
        source(kind).parseNovel("novel").chapters.orEmpty().last().releaseTime shouldBe null
    }

    @ParameterizedTest
    @EnumSource(Kind::class, names = ["APP", "IREADER"])
    fun `a chapter the source did not number has no number`(kind: Kind) = runTest {
        source(kind).parseNovel("novel").chapters.orEmpty().last().chapterNumber shouldBe null
    }

    @ParameterizedTest
    @EnumSource(Kind::class, names = ["APP", "IREADER"])
    fun `a catalogue without filters offers none`(kind: Kind) {
        source(kind).filters shouldBe null
    }

    @ParameterizedTest
    @EnumSource(Kind::class, names = ["APP", "IREADER"])
    fun `a catalogue carries its app's name and content warning`(kind: Kind) {
        source(kind).let { it.extensionName to it.contentWarning } shouldBe ("Pack" to ContentWarning.NSFW)
    }

    // The plugin format has no adult flag, so a plugin answers SAFE rather than a guess.
    @Test
    fun `a plugin is its own extension and warns of nothing`() {
        source(Kind.PLUGIN).let { it.extensionName to it.contentWarning } shouldBe ("P" to ContentWarning.SAFE)
    }

    private fun source(kind: Kind): NovelSource = when (kind) {
        Kind.PLUGIN -> LnPluginSource(pluginHost(), LnPluginInfo(id = "p", name = "P"))
        Kind.APP -> TachiyomiNovelSource(catalogue(), app())
        Kind.IREADER -> IReaderNovelSource(iReaderCatalogue(), app(Extension.Kind.IREADER))
    }

    private val listing = object : Listing("Latest") {}

    // IReader hands a chapter over as pages, and lists chapters oldest first as novels keep them.
    private fun iReaderCatalogue() = mockk<IReaderCatalogSource> {
        every { id } returns 7L
        every { name } returns "IReader"
        every { lang } returns "en"
        every { getFilters() } returns emptyList()
        every { getListings() } returns listOf(listing)
        coEvery { getMangaDetails(any(), any()) } returns MangaInfo(key = "novel", title = "Novel")
        coEvery { getChapterList(any(), any()) } returns
            listOf(ChapterInfo(key = "c1", name = "1", dateUpload = UPLOADED), ChapterInfo(key = "c2", name = "2"))
        coEvery { getPageList(any(), any()) } returns listOf(Text("a\u0000b"))
        coEvery { getMangaList(listing, 2) } returns
            MangasPageInfo(listOf(MangaInfo(key = "last", title = "last")), false)
    }

    private fun pluginHost() = mockk<LnPluginHost> {
        coEvery { parseNovel("p", "novel") } returns SourceNovel(
            path = "novel",
            chapters = listOf(ChapterItem(name = "1", path = "c1"), ChapterItem(name = "2", path = "c2")),
        )
        coEvery { parseChapter("p", "c1") } returns "<p>a\u0000b</p>"
        // A plugin says it has run out by answering with nothing.
        coEvery { popularNovels("p", 2, any()) } returns emptyList()
    }

    private fun catalogue() = mockk<CatalogueSource> {
        every { id } returns 7L
        every { name } returns "App"
        every { lang } returns "en"
        every { supportsLatest } returns false
        every { getFilterList() } returns FilterList()
        coEvery { getMangaUpdate(any(), any(), any(), any()) } answers {
            SMangaUpdate(firstArg(), listOf(chapter("c2"), chapter("c1").apply { date_upload = UPLOADED }))
        }
        coEvery { getPageList(any()) } returns listOf(Page(0, "c1"))
        coEvery { fetchPageText(any()) } returns "<p>a\u0000b</p>"
        coEvery { getPopularManga(2) } returns MangasPage(listOf(manga("last")), hasNextPage = false)
    }

    private fun chapter(url: String) = SChapter.create().apply {
        this.url = url
        name = url
    }

    private fun manga(url: String) = SManga.create().apply {
        this.url = url
        title = url
    }

    private fun app(kind: Extension.Kind = Extension.Kind.TACHIYOMI_NOVEL) = Extension.Loaded(
        name = "Pack",
        pkgName = "eu.kanade.tachiyomi.novelextension.en.app",
        versionName = "1.6.1",
        versionCode = 1,
        libVersion = 1.6,
        lang = "en",
        contentWarning = ContentWarning.NSFW,
        isShared = true,
        kind = kind,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
    )

    private companion object {
        const val UPLOADED = 1_700_000_000_123L
    }
}
