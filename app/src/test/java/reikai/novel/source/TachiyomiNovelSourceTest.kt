package reikai.novel.source

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.RateLimited
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import reikai.data.novel.NovelDateParser
import reikai.data.novel.NovelStatusCode

/** A novel extension app's catalogue crosses into the novel side's own forms. */
class TachiyomiNovelSourceTest {

    @Test
    fun `a status number arrives in the words novels store`() = runTest {
        val novel = source(details = { status = SManga.COMPLETED }).parseNovel("n")

        NovelStatusCode.fromString(novel.status) shouldBe NovelStatusCode.COMPLETED
    }

    @Test
    fun `an upload date survives to the millisecond`() = runTest {
        val chapter = source(chapter = { date_upload = 1_700_000_000_123L }).parseNovel("n").chapters!!.single()

        NovelDateParser.parse(chapter.releaseTime) shouldBe 1_700_000_000_123L
    }

    @Test
    fun `an unknown upload date is left unset`() = runTest {
        source(chapter = { date_upload = 0L }).parseNovel("n").chapters!!.single().releaseTime shouldBe null
    }

    @Test
    fun `an unknown chapter number is left unset`() = runTest {
        source(chapter = { chapter_number = -1f }).parseNovel("n").chapters!!.single().chapterNumber shouldBe null
    }

    @Test
    fun `a catalogue without filters offers none`() {
        source(filters = FilterList()).filters shouldBe null
    }

    @Test
    fun `a catalogue's filters travel with its search`() {
        source(filters = FilterList(Filter.Header("h"))).filters?.applyToSearch shouldBe true
    }

    @Test
    fun `an app's declared least delay reaches the novel side`() {
        source(minimumDelay = { 1_000L }).minimumRequestDelayMs shouldBe 1_000L
    }

    @Test
    fun `an app that declares no pacing asks for no delay`() {
        source().minimumRequestDelayMs shouldBe 0L
    }

    @Test
    fun `an app whose declared delay throws asks for none rather than failing`() {
        source(minimumDelay = { error("broken") }).minimumRequestDelayMs shouldBe 0L
    }

    @Test
    fun `a novel's web page is the one the app builds for it`() {
        webSource().webUrl("slug/", isNovel = true) shouldBe "https://site.example/series/slug/"
    }

    @Test
    fun `a chapter's web page is the one the app builds for it`() {
        webSource().webUrl("slug/", isNovel = false) shouldBe "https://site.example/read/slug/"
    }

    @Test
    fun `an app that cannot build a page falls back to its site`() {
        webSource(fails = true).webUrl("slug/", isNovel = true) shouldBe "https://site.example/slug/"
    }

    @Test
    fun `a chapter an app names only as its picture address is fetched from that address`() = runTest {
        textSource(Page(0, imageUrl = "https://site.example/read/c")).parseChapter("c") shouldBe
            "text of https://site.example/read/c"
    }

    @Test
    fun `a chapter page with its own address is fetched as the app gave it`() = runTest {
        textSource(Page(0, url = "https://site.example/read/c", imageUrl = "https://cdn.example/p.jpg"))
            .parseChapter("c") shouldBe "text of https://site.example/read/c"
    }

    /** An app whose page text echoes the address it was asked for. */
    private fun textSource(page: Page) = TachiyomiNovelSource(
        mockk<CatalogueSource>().apply {
            every { id } returns 7L
            every { name } returns "App"
            every { lang } returns "en"
            every { supportsLatest } returns false
            every { getFilterList() } returns FilterList()
            coEvery { getPageList(any()) } returns listOf(page)
            coEvery { fetchPageText(any()) } answers { "text of " + firstArg<Page>().url }
        },
        loadedExtension(),
    )

    /** An app whose novel path is a bare slug, which only its own url rule turns into a page. */
    private fun webSource(fails: Boolean = false) = TachiyomiNovelSource(
        mockk<HttpSource>().apply {
            every { id } returns 7L
            every { name } returns "App"
            every { lang } returns "en"
            every { supportsLatest } returns false
            every { baseUrl } returns "https://site.example/"
            every { getFilterList() } returns FilterList()
            every { getMangaUrl(any()) } answers {
                if (fails) error("broken")
                "https://site.example/series/" + firstArg<SManga>().url
            }
            every { getChapterUrl(any()) } answers { "https://site.example/read/" + firstArg<SChapter>().url }
        },
        loadedExtension(),
    )

    private fun source(
        details: SManga.() -> Unit = {},
        chapter: SChapter.() -> Unit = {},
        filters: FilterList = FilterList(),
        minimumDelay: (() -> Long)? = null,
    ) = TachiyomiNovelSource(
        catalogue(minimumDelay).apply {
            every { id } returns 7L
            every { name } returns "App"
            every { lang } returns "en"
            every { supportsLatest } returns false
            every { getFilterList() } returns filters
            coEvery { getMangaUpdate(any(), any(), any(), any()) } answers {
                val manga = SManga.create().apply {
                    url = "n"
                    title = "Novel"
                    details()
                }
                val chapters = listOf(
                    SChapter.create().apply {
                        url = "c"
                        name = "Chapter"
                        chapter()
                    },
                )
                SMangaUpdate(manga, chapters)
            }
        },
        loadedExtension(),
    )

    private fun loadedExtension() = Extension.Loaded(
        name = "App",
        pkgName = "eu.kanade.tachiyomi.novelextension.en.app",
        versionName = "1.6.1",
        versionCode = 1,
        libVersion = 1.6,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        isShared = true,
        kind = Extension.Kind.TACHIYOMI_NOVEL,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
    )

    /** A catalogue that declares a least delay when [minimumDelay] is given, as a library 1.6 app can. */
    private fun catalogue(minimumDelay: (() -> Long)?): CatalogueSource = if (minimumDelay == null) {
        mockk()
    } else {
        mockk<CatalogueSource>(moreInterfaces = arrayOf(RateLimited::class)).also {
            every { (it as RateLimited).minimumDelayMillis } answers { minimumDelay() }
        }
    }
}
