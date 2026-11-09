package reikai.novel.host

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/** What a plugin hands back decodes as LNReader reads it, so one off-spec value never fails the whole result. */
class PluginResultDecodingTest {

    private val json = LnPluginHost.JSON

    // JSON.stringify writes NaN as null, which a page count parsed from scraped text often is.
    @Test
    fun `a null page count reads as one page`() {
        json.decodeFromString(SourceNovel.serializer(), """{"path":"/x","totalPages":null}""").totalPages shouldBe 1
    }

    @Test
    fun `a listing item without a path drops alone`() {
        val items = json.decodeFromString(NovelItemsSerializer, """[{"name":"A","path":"/a"},{"name":"B"}]""")

        items.map { it.path } shouldBe listOf("/a")
    }

    @Test
    fun `a listing item without a name drops alone`() {
        val items = json.decodeFromString(NovelItemsSerializer, """[{"path":"/a"},{"name":"B","path":"/b"}]""")

        items.map { it.path } shouldBe listOf("/b")
    }

    @Test
    fun `a chapter without a name is named by its place in the list`() {
        val novel = json.decodeFromString(
            SourceNovel.serializer(),
            """{"path":"/n","chapters":[{"name":"One","path":"/1"},{"path":"/2"}]}""",
        )

        novel.chapters.orEmpty().map { it.name } shouldBe listOf("One", "Chapter 2")
    }

    @Test
    fun `a chapter without a path drops alone`() {
        val page = json.decodeFromString(
            SourcePage.serializer(),
            """{"chapters":[{"name":"One"},{"name":"Two","path":"/2"}]}""",
        )

        page.chapters.orEmpty().map { it.path } shouldBe listOf("/2")
    }

    // The reader showed the four letters "null", and a recorded fetch failure was never rethrown.
    @Test
    fun `a null chapter body is no text`() {
        chapterText(JsonNull) shouldBe ""
    }

    @Test
    fun `a chapter body comes through as it is`() {
        chapterText(JsonPrimitive("<p>text</p>")) shouldBe "<p>text</p>"
    }
}
