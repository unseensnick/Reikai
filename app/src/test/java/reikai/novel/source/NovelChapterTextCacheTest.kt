package reikai.novel.source

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import kotlin.time.Instant

class NovelChapterTextCacheTest {

    /** What the source answers now, so a load shows whether it asked the source or read the cache. */
    private var answer = "<p>first answer</p>"
    private var site = "https://example.test"

    private val chapterCache = testChapterCache()
    private val cache = testChapterTextCache(chapterCache)

    private fun loader(): NovelChapterTextLoader {
        val source = mockk<NovelSource> {
            every { site } answers { this@NovelChapterTextCacheTest.site }
            coEvery { parseChapter(any()) } answers { answer }
        }
        val sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get(any()) } returns source }
        return NovelChapterTextLoader(
            context = mockk(relaxed = true),
            novelRepo = mockk {
                coEvery { getById(any()) } answers
                    { Novel.create().copy(id = firstArg(), source = "s") }
            },
            sourceManager = sourceManager,
            installer = mockk(relaxed = true),
            preferences = NovelPreferences(InMemoryPreferenceStore()),
            textCache = cache,
            readDownloaded = { _, _ -> null },
        )
    }

    /** A new loader, as a new reading session builds, so only the disk cache can carry the text over. */
    @Test
    fun `a chapter opened again in a new session reads the text fetched before`() = runTest {
        loader().load(chapter(novelId = 1L))
        answer = "<p>second answer</p>"

        val (html, _) = loader().load(chapter(novelId = 1L))

        html shouldContain "first answer"
    }

    @Test
    fun `a load from the source replaces the cached text`() = runTest {
        loader().load(chapter(novelId = 1L))
        answer = "<p>second answer</p>"
        loader().load(chapter(novelId = 1L), fromSource = true)

        val (html, _) = loader().load(chapter(novelId = 1L))

        html shouldContain "second answer"
    }

    @Test
    fun `a cached chapter keeps the source site its links resolve against`() = runTest {
        loader().load(chapter(novelId = 1L))
        site = "https://moved.test"

        val (_, baseUrl) = loader().load(chapter(novelId = 1L))

        baseUrl.orEmpty() shouldContain "example.test"
    }

    /** Two sources can use the same chapter path, so the cache keys on the novel as well. */
    @Test
    fun `another novel's chapter at the same path is not read from the cache`() = runTest {
        loader().load(chapter(novelId = 1L))
        answer = "<p>second answer</p>"

        val (html, _) = loader().load(chapter(novelId = 2L))

        html shouldContain "second answer"
    }

    /** The text a source answered badly is dropped when asked for again, as manga drops its pages first. */
    @Test
    fun `a failed load from the source leaves no cached text behind`() = runTest {
        loader().load(chapter(novelId = 1L))
        answer = ""
        shouldThrow<EmptyChapterException> { loader().load(chapter(novelId = 1L), fromSource = true) }
        answer = "<p>second answer</p>"

        val (html, _) = loader().load(chapter(novelId = 1L))

        html shouldContain "second answer"
    }

    /** Without a separator, novel 1's path "2/ch.html" and novel 12's "/ch.html" make the same key. */
    @Test
    fun `a novel whose id runs into another's chapter path is not read from its cache`() = runTest {
        loader().load(chapter(novelId = 1L, url = "2/ch.html"))
        answer = "<p>second answer</p>"

        val (html, _) = loader().load(chapter(novelId = 12L, url = "/ch.html"))

        html shouldContain "second answer"
    }

    /** Manga's page lists share the cache, and a plugin's path can be a manga's without its leading slash. */
    @Test
    fun `a novel chapter's text leaves a manga chapter's cached pages alone`() {
        val mangaChapter = Chapter.create().copy(mangaId = 1L, url = "/book/ch1.html")
        chapterCache.putPageListToCache(mangaChapter, listOf(Page(0, "https://example.test/1")))

        cache.put(chapter(novelId = 1L, url = "book/ch1.html"), CachedChapterText("x", null))

        chapterCache.getPageListFromCache(mangaChapter).map { it.url } shouldBe listOf("https://example.test/1")
    }

    private fun chapter(novelId: Long, url: String = "/book/ch1.html") = NovelChapter(
        id = novelId,
        novelId = novelId,
        url = url,
        name = "Chapter 1",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = 1.0,
        sourceOrder = 0L,
        dateFetch = Instant.fromEpochMilliseconds(0L),
        dateUpload = null,
        page = "",
    )
}
