package reikai.novel.download

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadedTextsFixture.chapter

/** Novel 1 alone, chapters 10..13 listed by the source out of order; 13 is hidden. */
class NovelDownloadedTextsTest {

    private val chapters =
        listOf(chapter(12, order = 3), chapter(10, order = 1), chapter(13, order = 4), chapter(11, 2))

    private fun texts(stored: Map<Long, String?>) =
        NovelDownloadedTextsFixture.texts(chapters, stored, hidden = setOf("u13"))

    @Test
    fun `the chapters on disk come in reading order`() = runTest {
        texts(mapOf(12L to "c", 10L to "a")).chaptersOf(1L, sourceScoped = false).onDisk.map { it.id } shouldBe
            listOf(10L, 12L)
    }

    @Test
    fun `a hidden chapter is left out even on disk`() = runTest {
        texts(mapOf(13L to "d")).chaptersOf(1L, sourceScoped = false).onDisk shouldBe emptyList()
    }

    @Test
    fun `the total counts every shown chapter, on disk or not`() = runTest {
        texts(mapOf(10L to "a")).chaptersOf(1L, sourceScoped = false).total shouldBe 3
    }

    @Test
    fun `an unreadable file reads as null and a readable one as its visible text`() = runTest {
        val texts = texts(mapOf(10L to "<p>One <b>two</b></p>", 11L to null))
        val read = mutableListOf<Pair<Long, String?>>()

        texts.readEach(listOf(chapter(10, 1), chapter(11, 2))) { ch, text -> read += ch.id to text }

        read shouldBe listOf(10L to "One two", 11L to null)
    }

    @Test
    fun `a page's head, scripts and styles are not its text`() = runTest {
        val stored = "<html><head><title>Ignored</title><style>.r{}</style><script>var r;</script></head>" +
            "<body><h1>Title</h1><p>The <b>Rogue</b> ran.</p></body></html>"

        visibleText(stored, chapter(10, 1)) shouldBe "Title The Rogue ran."
    }

    @Test
    fun `a plain-text chapter keeps an angle bracket as text`() = runTest {
        visibleText("a <b and c", chapter(10, 1).copy(url = "u10.txt")) shouldBe "a <b and c"
    }

    private suspend fun visibleText(stored: String, chapter: NovelChapter): String? {
        var text: String? = null
        texts(mapOf(chapter.id to stored)).readEach(listOf(chapter)) { _, read -> text = read }
        return text
    }
}
