package reikai.data.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel
import reikai.novel.host.NovelItem

/** A browsed result becomes a row that exists only once stored, carrying what the list drew. */
class NovelItemMappingTest {

    @Test
    fun `a browsed result maps to an unsaved row on its source`() {
        NovelItem(name = "A novel", path = "/a", cover = "https://cover.test/a.jpg").toNovel("src") shouldBe
            Novel.create().copy(
                source = "src",
                url = "/a",
                title = "A novel",
                thumbnailUrl = "https://cover.test/a.jpg",
            )
    }
}
