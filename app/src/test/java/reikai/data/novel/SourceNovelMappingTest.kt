package reikai.data.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.novel.host.SourceNovel

/** A plugin's parsed details as the row stores them. */
class SourceNovelMappingTest {

    @Test
    fun `a genre the plugin lists twice is stored once`() {
        SourceNovel(path = "/a", genres = "Action, Fantasy, Action").toNovel("src").genre shouldBe
            listOf("Action", "Fantasy")
    }
}
