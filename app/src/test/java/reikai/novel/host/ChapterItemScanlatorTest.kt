package reikai.novel.host

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.toNovelChapter

/**
 * A plugin names a chapter's group as a string or a string array (lnreader's `ChapterItem.scanlator`),
 * decoded the way lnreader stores it and through the decode path `parseNovel` uses.
 */
class ChapterItemScanlatorTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    fun `a plugin's scanlator decodes to one group string`(field: String, expected: String?) {
        val raw = Json.parseToJsonElement("""{"path":"/n","chapters":[{"name":"c","path":"/c"$field}]}""")

        val novel = LnPluginHost.JSON.decodeFromJsonElement(SourceNovel.serializer(), raw)

        novel.chapters!!.single().scanlator shouldBe expected
    }

    // Every other text a plugin sends is HTML-decoded on the way in.
    @Test
    fun `a stored group is decoded`() {
        ChapterItem(name = "c", path = "/c", scanlator = "A &amp; B").toNovelChapter(novelId = 1L).scanlator shouldBe
            "A & B"
    }

    companion object {
        @JvmStatic
        fun shapes() = listOf(
            Arguments.of(""","scanlator":"Group"""", "Group"),
            Arguments.of(""","scanlator":["A","","B"]""", "A, B"),
            Arguments.of(""","scanlator":["A",null]""", "A"),
            Arguments.of(""","scanlator":[]""", ""),
            Arguments.of(""","scanlator":null""", null),
            Arguments.of("", null),
            Arguments.of(""","scanlator":5""", "5"),
            Arguments.of(""","scanlator":{"x":1}""", ""),
        )
    }
}
