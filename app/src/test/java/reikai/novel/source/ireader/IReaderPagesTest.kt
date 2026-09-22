package reikai.novel.source.ireader

import io.kotest.matchers.shouldBe
import ireader.core.source.model.ImageBase64
import ireader.core.source.model.ImageUrl
import ireader.core.source.model.MovieUrl
import ireader.core.source.model.PageUrl
import ireader.core.source.model.Text
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class IReaderPagesTest {

    private val unresolved: suspend (PageUrl) -> Nothing? = { null }

    @Test
    fun `a text page becomes an escaped paragraph`() = runTest {
        listOf(Text("a < b")).toChapterHtml(unresolved) shouldBe "<p>a &lt; b</p>"
    }

    @Test
    fun `a linked picture is shown`() = runTest {
        listOf(ImageUrl("https://s.example/p.png")).toChapterHtml(unresolved) shouldBe
            "<img src=\"https://s.example/p.png\">"
    }

    @Test
    fun `an embedded picture is shown through a data URI`() = runTest {
        listOf(ImageBase64("iVBOR")).toChapterHtml(unresolved) shouldBe "<img src=\"data:image/png;base64,iVBOR\">"
    }

    @Test
    fun `an embedded picture that is already a data URI is kept as it is`() = runTest {
        listOf(ImageBase64("data:image/jpeg;base64,/9j")).toChapterHtml(unresolved) shouldBe
            "<img src=\"data:image/jpeg;base64,/9j\">"
    }

    @Test
    fun `a page that links to its content is fetched`() = runTest {
        listOf(PageUrl("next")).toChapterHtml { Text("fetched") } shouldBe "<p>fetched</p>"
    }

    @Test
    fun `a linked page the source cannot resolve is left out`() = runTest {
        listOf(Text("kept"), PageUrl("next")).toChapterHtml(unresolved) shouldBe "<p>kept</p>"
    }

    @Test
    fun `video is left out`() = runTest {
        listOf(MovieUrl("https://s.example/v.mp4")).toChapterHtml(unresolved) shouldBe ""
    }
}
