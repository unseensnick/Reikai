package reikai.domain.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

/** The manga tiers over the shared-link kernel, with the extensions faked and the library a map. */
class ResolveMangaLinkTest {

    private val stored = mutableMapOf<Pair<String, Long>, Manga>()

    private fun resolver(vararg sources: HttpSource) = ResolveMangaLink(
        onlineSources = { sources.toList() },
        storedManga = { url, id -> stored[url to id] },
        networkToLocalManga = { it.copy(id = 99L) },
    )

    private fun source(
        id: Long,
        baseUrl: String = "https://example.com",
        mangaUrl: (String) -> String = { baseUrl + it },
        // Only the series' own address is a series, as on a site that serves a listing one level up.
        series: (String) -> SMangaUpdate? = { path ->
            update(path, "Series".takeIf { path.trimStart('/') == "series/1" }, chapters = 3)
        },
        search: (String) -> List<String> = { emptyList() },
    ) = mockk<HttpSource> {
        every { this@mockk.id } returns id
        every { this@mockk.baseUrl } returns baseUrl
        every { getMangaUrl(any()) } answers { mangaUrl(firstArg<SManga>().url) }
        every { getFilterList() } returns FilterList()
        coEvery { getMangaUpdate(any(), any(), any(), any()) } answers {
            series(firstArg<SManga>().url) ?: error("404")
        }
        coEvery { getSearchManga(1, any(), any()) } answers {
            MangasPage(search(secondArg()).map { manga(it) }, hasNextPage = false)
        }
    }

    private fun manga(path: String) = SManga.create().apply {
        url = path
        title = path
    }

    private fun update(path: String, title: String?, chapters: Int, chapterPath: String = "/c/") = SMangaUpdate(
        SManga.create().apply {
            url = path
            if (title != null) this.title = title
        },
        List(chapters) { SChapter.create().apply { url = "$chapterPath$it" } },
    )

    @Test
    fun `one source serving the link's site opens the series`() = runTest {
        resolver(source(1L)).byGuess(LINK)!!.url shouldBe "/series/1"
    }

    @Test
    fun `two sources on one site open nothing`() = runTest {
        resolver(source(1L), source(2L)).byGuess(LINK) shouldBe null
    }

    @Test
    fun `a page with no chapters is refused`() = runTest {
        resolver(source(1L, series = { ownPage(it, update(it, "Series", chapters = 0)) })).byGuess(LINK) shouldBe null
    }

    @Test
    fun `a page with a blank title is refused`() = runTest {
        resolver(source(1L, series = { ownPage(it, update(it, " ", chapters = 3)) })).byGuess(LINK) shouldBe null
    }

    @Test
    fun `a page with no title is refused`() = runTest {
        resolver(source(1L, series = { ownPage(it, update(it, null, chapters = 3)) })).byGuess(LINK) shouldBe null
    }

    /** [page] at the series' own address, and a real listing one level up, so the parent probe passes. */
    private fun ownPage(path: String, page: SMangaUpdate) =
        if (path.trimStart('/') == "series/1") page else update(path, "Browse", chapters = 3)

    @Test
    fun `an extension whose address rule throws names nothing`() = runTest {
        resolver(source(1L, mangaUrl = { error("no rule") })).byGuess(LINK) shouldBe null
    }

    @Test
    fun `a chapter link on an extension that answers any address below a series is refused`() = runTest {
        val anyBelow = source(1L, series = { update(it, "Series", chapters = 3) })

        resolver(anyBelow).byGuess("https://example.com/series/1/chapter-2") shouldBe null
    }

    @Test
    fun `a site that also joins a path without its leading slash stores the extension's own spelling`() = runTest {
        val source = source(1L, baseUrl = "https://example.com/", series = {
            update(
                it,
                "Series".takeIf { _ ->
                    it.trimStart('/') == "series/1"
                },
                chapters = 3,
                chapterPath = "/series/1/c",
            )
        })

        resolver(source).byGuess(LINK)!!.url shouldBe "/series/1"
    }

    @Test
    fun `a stored series opens without asking the extension`() = runTest {
        stored["/series/1/" to 1L] = Manga.create().copy(id = 5L, url = "/series/1/", source = 1L)

        resolver(source(1L, series = { error("not asked") })).byStoredRow(LINK)!!.id shouldBe 5L
    }

    @Test
    fun `a stored series is not guessed again`() = runTest {
        stored["/series/1" to 1L] = Manga.create().copy(id = 5L, url = "/series/1", source = 1L)

        resolver(source(1L)).byGuess(LINK) shouldBe null
    }

    @Test
    fun `the site's own search finding the linked series opens it`() = runTest {
        resolver(source(1L, search = { listOf("/series/1") })).bySearch(LINK)!!.url shouldBe "/series/1"
    }

    @Test
    fun `a search with more than one result opens nothing`() = runTest {
        resolver(source(1L, search = { listOf("/series/1", "/series/2") })).bySearch(LINK) shouldBe null
    }

    @Test
    fun `a search whose one result is another series opens nothing`() = runTest {
        resolver(source(1L, search = { listOf("/series/2") })).bySearch(LINK) shouldBe null
    }

    @Test
    fun `an extension on another site is not searched`() = runTest {
        // Its search would find the series, so only the site check keeps it from being asked.
        val elsewhere = source(
            1L,
            baseUrl = "https://other.example",
            mangaUrl = { "https://example.com$it" },
            search = { listOf("/series/1") },
        )

        resolver(elsewhere).bySearch(LINK) shouldBe null
    }

    @Test
    fun `text that is not a link is not searched`() = runTest {
        resolver(source(1L, search = { listOf("/series/1") })).bySearch("series one") shouldBe null
    }

    private companion object {
        const val LINK = "https://www.example.com/series/1?utm_source=share"
    }
}
