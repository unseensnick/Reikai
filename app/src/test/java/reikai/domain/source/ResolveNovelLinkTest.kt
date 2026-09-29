package reikai.domain.source

import android.content.Context
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.novel.model.Novel
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelLink
import reikai.novel.source.NovelLinkResolver
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.domain.library.service.LibraryPreferences
import java.io.File

/** The three novel tiers of a shared link over the real SQL, with the sources faked. */
class ResolveNovelLinkTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var novels: NovelRepositoryImpl
    private lateinit var chapters: NovelChapterRepositoryImpl
    private lateinit var coverCache: CoverCache
    private var loads = 0

    @TempDir
    lateinit var cacheRoot: File

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            database = DatabaseBindings.providesDatabase(driver)
            novels = NovelRepositoryImpl(database)
            chapters = NovelChapterRepositoryImpl(database)
            val context = mockk<Context> {
                every { getExternalFilesDir(any()) } answers { File(cacheRoot, firstArg<String>()).apply { mkdirs() } }
            }
            coverCache = CoverCache(context)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private fun resolver(vararg sources: NovelSource) = ResolveNovelLink(
        loadedSources = {
            loads++
            sources.toList()
        },
        novelRepository = novels,
        novelChapterRepository = chapters,
        libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        coverCache = coverCache,
    )

    private suspend fun stored(url: String, sourceId: String = "a"): Novel {
        val id = novels.insert(Novel.create().copy(source = sourceId, url = url, title = "Stored", favoriteAt = 0L))!!
        return novels.getById(id)!!
    }

    @Test
    fun `a source that reads its own links names the novel`() = runTest {
        val hooked = FakeSource("a", readLink = { NovelLink.Novel("/hooked") })

        resolver(hooked).byHook(LINK) shouldBe NovelLinkTarget.Novel("a", "/hooked")
    }

    @Test
    fun `a chapter link missing from a stored novel refreshes it and opens the chapter`() = runTest {
        val novel = stored("/novel/1")
        val source = FakeSource(
            "a",
            readLink = { NovelLink.Chapter("/novel/1", "/c/9") },
            parse = { SourceNovel(it, name = "N", chapters = listOf(ChapterItem("9", "/c/9"))) },
        )

        val target = resolver(source).byHook(LINK) as NovelLinkTarget.Chapter

        target.chapterId shouldBe chapters.getByUrlAndNovelId("/c/9", novel.id)!!.id
    }

    @Test
    fun `a chapter link for a novel not stored yet stores it and opens the chapter`() = runTest {
        val source = FakeSource(
            "a",
            readLink = { NovelLink.Chapter("/novel/1", "/c/9") },
            parse = { SourceNovel(it, name = "N", chapters = listOf(ChapterItem("9", "/c/9"))) },
        )

        val target = resolver(source).byHook(LINK) as NovelLinkTarget.Chapter

        target.novelId shouldBe novels.getByUrlAndSource("/novel/1", "a")!!.id
    }

    @Test
    fun `a stored row opens without asking the source`() = runTest {
        stored("/novel/1")
        val source = FakeSource("a", parse = { error("not asked") })

        resolver(source).byStoredRow(LINK) shouldBe NovelLinkTarget.Novel("a", "/novel/1")
    }

    @Test
    fun `a stored row with the other trailing slash opens`() = runTest {
        stored("novel/1/")

        resolver(FakeSource("a")).byStoredRow(LINK) shouldBe NovelLinkTarget.Novel("a", "novel/1/")
    }

    @Test
    fun `a stored row is not guessed again`() = runTest {
        stored("/novel/1")

        resolver(FakeSource("a")).byGuess(LINK) shouldBe null
    }

    @Test
    fun `a guess that parses to a novel opens it`() = runTest {
        resolver(FakeSource("a")).byGuess(LINK) shouldBe NovelLinkTarget.Novel("a", "novel/1")
    }

    @Test
    fun `a guessed novel is stored outside the library`() = runTest {
        resolver(FakeSource("a")).byGuess(LINK)

        novels.getByUrlAndSource("novel/1", "a")!!.favorite shouldBe false
    }

    // Novel Hall: its site ends in `/` and its own paths start with one, so its browse stores `/x`.
    @Test
    fun `a guess on a site that also joins a leading slash is stored as the source spells its paths`() = runTest {
        val source = FakeSource("a", chapterPath = "/novel/1/c1.html")

        resolver(source).byGuess(LINK) shouldBe NovelLinkTarget.Novel("a", "/novel/1")
    }

    @Test
    fun `a guess whose spelling nothing the source returned settles is refused`() = runTest {
        val source =
            FakeSource("a", parse = { SourceNovel(it, name = named(it), chapters = emptyList(), totalPages = 3) })

        resolver(source).byGuess(LINK) shouldBe null
    }

    @Test
    fun `a paged guess with no first-page chapters opens when only one spelling names the link`() = runTest {
        val source = FakeSource(
            "a",
            site = "https://example.com",
            parse = { SourceNovel(it, name = named(it), chapters = emptyList(), totalPages = 3) },
        )

        resolver(source).byGuess(LINK) shouldBe NovelLinkTarget.Novel("a", "/novel/1")
    }

    @Test
    fun `a guess whose page has no name is refused`() = runTest {
        val source =
            FakeSource("a", parse = { ownPage(it, SourceNovel(it, chapters = listOf(ChapterItem("1", "c/1")))) })

        resolver(source).byGuess(LINK) shouldBe null
    }

    @Test
    fun `a guess whose page has a blank name is refused`() = runTest {
        val source =
            FakeSource("a", parse = {
                ownPage(it, SourceNovel(it, name = " ", chapters = listOf(ChapterItem("1", "c/1"))))
            })

        resolver(source).byGuess(LINK) shouldBe null
    }

    // Plugins fill a name their page lacks with one of these (novelhall.ts, and 39 plugins with `Untitled`).
    @ParameterizedTest
    @ValueSource(strings = ["Untitled", "No Title Found"])
    fun `a guess whose page has only a placeholder name is refused`(placeholder: String) = runTest {
        val source = FakeSource(
            "a",
            parse = { ownPage(it, SourceNovel(it, name = placeholder, chapters = listOf(ChapterItem("1", "c/1")))) },
        )

        resolver(source).byGuess(LINK) shouldBe null
    }

    // A site with no trailing slash, so only one spelling names the link and nothing else refuses it.
    @Test
    fun `a guess whose page lists no chapters is refused`() = runTest {
        val source = FakeSource(
            "a",
            site = "https://example.com",
            parse = { ownPage(it, SourceNovel(it, name = "Novel", chapters = emptyList())) },
        )

        resolver(source).byGuess(LINK) shouldBe null
    }

    @Test
    fun `a guess that throws is refused`() = runTest {
        resolver(FakeSource("a", parse = { error("404") })).byGuess(LINK) shouldBe null
    }

    // WuxiaWorld's plugin reads the novel from one path segment, so it parses a chapter's address as the
    // whole novel, name and chapters included; measured on device against the live plugin.
    @Test
    fun `a chapter link on a source that answers any address below a novel is refused`() = runTest {
        val source = FakeSource(
            "a",
            parse = { SourceNovel(it, name = "Child of Light", chapters = listOf(ChapterItem("1", "$it/c1"))) },
        )

        resolver(source).byGuess("https://example.com/novel/child-of-light/chapter-2") shouldBe null
    }

    @Test
    fun `a novel whose parent address is another page still opens`() = runTest {
        val source = FakeSource(
            "a",
            parse = {
                SourceNovel(
                    it,
                    name = if (it ==
                        "novel/1"
                    ) {
                        "N"
                    } else {
                        "Browse"
                    },
                    chapters = listOf(ChapterItem("1", "c/1")),
                )
            },
        )

        resolver(source).byGuess(LINK) shouldBe NovelLinkTarget.Novel("a", "novel/1")
    }

    @Test
    fun `two sources on the link's site guess nothing`() = runTest {
        resolver(FakeSource("a"), FakeSource("b")).byGuess(LINK) shouldBe null
    }

    @Test
    fun `a source on another site is not guessed`() = runTest {
        resolver(FakeSource("a", site = "https://other.example/")).byGuess(LINK) shouldBe null
    }

    @Test
    fun `a source whose address rule never gives the link is not guessed`() = runTest {
        resolver(FakeSource("a", resolve = { "https://example.com/book/$it" })).byGuess(LINK) shouldBe null
    }

    @Test
    fun `text that is not a link never loads the sources`() = runTest {
        val resolver = resolver(FakeSource("a"))

        resolver.byHook("mother of learning")
        resolver.byStoredRow("mother of learning")
        resolver.byGuess("mother of learning")

        loads shouldBe 0
    }

    @Test
    fun `the sources load once for every tier`() = runTest {
        val resolver = resolver(FakeSource("a"))

        resolver.byHook(LINK)
        resolver.byStoredRow(LINK)
        resolver.byGuess(LINK)

        loads shouldBe 1
    }

    private class FakeSource(
        override val id: String,
        override val site: String = "https://example.com/",
        private val readLink: ((String) -> NovelLink?)? = null,
        private val chapterPath: String = "novel/1/c1",
        private val resolve: ((String) -> String?)? = null,
        // Only the novel's own address parses to it, as on a site that serves a listing one level up.
        private val parse: (String) -> SourceNovel = {
            SourceNovel(it, name = named(it), chapters = listOf(ChapterItem("1", chapterPath)))
        },
    ) : NovelSource {
        override val name = id
        override val version = "1"
        override val lang = "en"
        override val iconUrl: String? = null
        override val format = NovelExtensionFormat.JS
        override val extensionName = id
        override val contentWarning = ContentWarning.SAFE
        override val links: NovelLinkResolver? = readLink?.let { f -> NovelLinkResolver { f(it) } }

        override suspend fun resolveUrl(path: String, isNovel: Boolean): String? = resolve?.invoke(path)
        override suspend fun parseNovel(novelPath: String) = parse(novelPath)
        override suspend fun parseChapter(chapterPath: String): String = unused()
        override suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): NovelItemsPage =
            unused()
        override suspend fun search(query: String, page: Int, filters: NovelFilterState?): NovelItemsPage = unused()
        private fun unused(): Nothing = throw UnsupportedOperationException()
    }

    private companion object {
        const val LINK = "https://www.example.com/novel/1?utm_source=share"

        fun isOwnAddress(path: String) = path == "novel/1" || path == "/novel/1"

        /** The novel's name at its own address, and none one level up, where a listing is. */
        fun named(path: String) = "Novel".takeIf { isOwnAddress(path) }

        /** [page] at the novel's own address, and a real listing page one level up, so the parent probe passes. */
        fun ownPage(path: String, page: SourceNovel) =
            if (isOwnAddress(
                    path,
                )
            ) {
                page
            } else {
                SourceNovel(path, name = "Browse", chapters = listOf(ChapterItem("1", "c/1")))
            }
    }
}
