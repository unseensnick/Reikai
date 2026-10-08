package reikai.presentation.details

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import tachiyomi.domain.manga.model.Manga

/**
 * The page the details screen's assistant link, WebView, Share and Copy link all open. The address rule
 * is parameterized over both content types, since one rule decides for both.
 */
class EntryWebPageTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "   "])
    fun `a missing or blank address gives no page, so the web actions hide`(url: String?) {
        EntryWebPage.of(url, SourceKey.Manga(1L), EntryId.Manga(2L)) shouldBe null
    }

    @ParameterizedTest
    @MethodSource("members")
    fun `a real address gives the viewed member's page on its own source`(source: SourceKey, viewed: EntryId) {
        EntryWebPage.of(PAGE, source, viewed) shouldBe EntryWebPage(PAGE, source, viewed)
    }

    @Test
    fun `a manga on a source with no web site has no page`() {
        anchor.webPageIn(mockk<Source> { every { id } returns 0L }) shouldBe null
    }

    @Test
    fun `a manga whose extension cannot address it has no page, rather than a crash`() {
        val source = mockk<HttpSource> {
            every { id } returns 7L
            every { getMangaUrl(any()) } throws IllegalStateException("no address")
        }

        anchor.webPageIn(source) shouldBe null
    }

    @Test
    fun `a manga's page is the address its extension gives`() {
        anchor.webPageIn(http(7L)) shouldBe EntryWebPage("$PAGE/1", SourceKey.Manga(7L), EntryId.Manga(1L))
    }

    @Test
    fun `a merged manga's page follows the chip`() {
        val anchorSource = http(7L)
        val siblingSource = http(8L)
        val page = ShownWebPage()

        val pages = listOf(anchor to anchorSource, sibling to siblingSource, anchor to anchorSource)
            .map { (manga, source) -> page.of(manga, source) }

        pages shouldBe listOf(
            EntryWebPage("$PAGE/1", SourceKey.Manga(7L), EntryId.Manga(1L)),
            EntryWebPage("$PAGE/2", SourceKey.Manga(8L), EntryId.Manga(2L)),
            EntryWebPage("$PAGE/1", SourceKey.Manga(7L), EntryId.Manga(1L)),
        )
    }

    @Test
    fun `a re-render of the same member does not ask its extension again`() {
        val source = http(7L)
        val page = ShownWebPage()

        repeat(3) { page.of(anchor, source) }

        verify(exactly = 1) { source.getMangaUrl(any()) }
    }

    private fun http(sourceId: Long) = mockk<HttpSource> {
        every { id } returns sourceId
        every { getMangaUrl(any()) } answers { PAGE + firstArg<SManga>().url }
    }

    companion object {
        private const val PAGE = "https://example.org/series"
        private val anchor = Manga.create().copy(id = 1L, url = "/1")
        private val sibling = Manga.create().copy(id = 2L, url = "/2")

        @JvmStatic
        fun members() = listOf(
            Arguments.of(SourceKey.Manga(4_793_064_155_310_670_919L), EntryId.Manga(12L)),
            Arguments.of(SourceKey.Novel("novelfire"), EntryId.Novel(34L)),
        )
    }
}
