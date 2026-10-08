package reikai.novel.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.source.CatalogueEnd
import reikai.novel.host.NovelItem

/** A novel page lists each novel once, by the rule a manga page follows (`listedOnce`). */
class ListedNovelsTest {

    @Test
    fun `a path the page lists twice is listed once, in source order`() {
        val page = NovelItemsPage(
            listOf(NovelItem("A", "/a"), NovelItem("B", "/b"), NovelItem("A again", "/a")),
            CatalogueEnd.Reported(hasNextPage = false),
        )
        page.listedNovels().map { it.path } shouldBe listOf("/a", "/b")
    }
}
