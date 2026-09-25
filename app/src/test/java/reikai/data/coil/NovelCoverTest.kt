package reikai.data.coil

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel

class NovelCoverTest {

    private val novel = Novel.create().copy(
        id = 7L,
        source = "plugin",
        favorite = true,
        thumbnailUrl = "https://stored",
        coverLastModified = 42L,
    )

    @Test
    fun `a novel's cover carries its own identity`() {
        novel.asNovelCover() shouldBe NovelCover(
            url = "https://stored",
            sourceId = "plugin",
            isNovelFavorite = true,
            lastModified = 42L,
            novelId = 7L,
        )
    }

    @Test
    fun `a url override replaces the stored thumbnail`() {
        novel.asNovelCover(url = "https://typed").url shouldBe "https://typed"
    }
}
