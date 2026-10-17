package reikai.novel.source

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
import kotlin.time.Instant

class NovelChapterTextCacheTest {

    /** What the source answers now, so a load shows whether it asked the source or read the cache. */
    private var answer = "<p>first answer</p>"
    private var site = "https://example.test"

    private val cache = testChapterTextCache()

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

    private fun chapter(novelId: Long) = NovelChapter(
        id = novelId,
        novelId = novelId,
        url = "/book/ch1.html",
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
