package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel

/** A browsed novel has no id, so whether it is in the library is a question about its source and url. */
class FavoritedNovelsTest {

    private fun novel(source: String, url: String, favorite: Boolean) =
        Novel.create().copy(source = source, url = url, favoriteAt = 1L.takeIf { favorite })

    @Test
    fun `the same path under another source is not in the library`() {
        FavoritedNovels.of(listOf(novel("a", "/n", favorite = true))).contains("b", "/n") shouldBe false
    }

    @Test
    fun `a favorite is in the library`() {
        FavoritedNovels.of(listOf(novel("a", "/n", favorite = true))).contains("a", "/n") shouldBe true
    }

    @Test
    fun `a stored row outside the library is not`() {
        FavoritedNovels.of(listOf(novel("a", "/n", favorite = false))).contains("a", "/n") shouldBe false
    }
}
