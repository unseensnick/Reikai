package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga

class ShownEntryTest {

    @Test
    fun `a manga chip shows the chip's synopsis and tags under the anchor's custom title`() {
        val anchor = Manga.create().copy(id = 1L, description = "anchor", genre = listOf("Action"))
        val chip = Manga.create().copy(id = 2L, description = "chip", genre = listOf("Romance"))
        val overlay = CustomMangaInfo(mangaId = 1L, title = "Custom")

        val shown = shownManga(anchor, chip, overlay, null)

        Triple(shown.title, shown.description, shown.genre) shouldBe Triple("Custom", "chip", listOf("Romance"))
    }

    @Test
    fun `a novel chip shows the chip's synopsis and tags under the anchor's custom title`() {
        val anchor = Novel.create().copy(id = 1L, description = "anchor", genre = listOf("Action"))
        val chip = Novel.create().copy(id = 2L, description = "chip", genre = listOf("Romance"))
        val overlay = CustomNovelInfo(novelId = 1L, title = "Custom")

        val shown = shownNovel(anchor, chip, overlay, null)

        Triple(shown.title, shown.description, shown.genre) shouldBe Triple("Custom", "chip", listOf("Romance"))
    }

    @Test
    fun `a manga chip keeps its own cover under the anchor's custom cover url`() {
        val anchor = Manga.create().copy(id = 1L, thumbnailUrl = "anchor")
        val chip = Manga.create().copy(id = 2L, thumbnailUrl = "chip")
        val overlay = CustomMangaInfo(mangaId = 1L, thumbnailUrl = "custom")

        shownManga(anchor, chip, overlay, null).thumbnailUrl shouldBe "chip"
    }

    @Test
    fun `a novel chip keeps its own cover under the anchor's custom cover url`() {
        val anchor = Novel.create().copy(id = 1L, thumbnailUrl = "anchor")
        val chip = Novel.create().copy(id = 2L, thumbnailUrl = "chip")
        val overlay = CustomNovelInfo(novelId = 1L, thumbnailUrl = "custom")

        shownNovel(anchor, chip, overlay, null).thumbnailUrl shouldBe "chip"
    }

    @Test
    fun `with no chip selected the anchor shows its custom cover url`() {
        val anchor = Manga.create().copy(id = 1L, thumbnailUrl = "anchor")
        val overlay = CustomMangaInfo(mangaId = 1L, thumbnailUrl = "custom")

        shownManga(anchor, null, overlay, null).thumbnailUrl shouldBe "custom"
    }

    @Test
    fun `a manga chip's own custom title does not replace the anchor's`() {
        val anchor = Manga.create().copy(id = 1L, title = "anchor")
        val chip = Manga.create().copy(id = 2L, title = "chip")
        val own = CustomMangaInfo(mangaId = 2L, title = "Chip custom", thumbnailUrl = "chip-custom")

        shownManga(anchor, chip, CustomMangaInfo(mangaId = 1L, title = "Custom"), own).title shouldBe "Custom"
    }

    @Test
    fun `a novel chip's own custom title does not replace the anchor's`() {
        val anchor = Novel.create().copy(id = 1L, title = "anchor")
        val chip = Novel.create().copy(id = 2L, title = "chip")
        val own = CustomNovelInfo(novelId = 2L, title = "Chip custom", thumbnailUrl = "chip-custom")

        shownNovel(anchor, chip, CustomNovelInfo(novelId = 1L, title = "Custom"), own).title shouldBe "Custom"
    }

    @Test
    fun `a manga chip ignores a custom row read for another entry`() {
        val anchor = Manga.create().copy(id = 1L)
        val chip = Manga.create().copy(id = 2L, thumbnailUrl = "chip")
        val stale = CustomMangaInfo(mangaId = 3L, thumbnailUrl = "other")

        shownManga(anchor, chip, null, stale).thumbnailUrl shouldBe "chip"
    }

    @Test
    fun `a novel chip ignores a custom row read for another entry`() {
        val anchor = Novel.create().copy(id = 1L)
        val chip = Novel.create().copy(id = 2L, thumbnailUrl = "chip")
        val stale = CustomNovelInfo(novelId = 3L, thumbnailUrl = "other")

        shownNovel(anchor, chip, null, stale).thumbnailUrl shouldBe "chip"
    }
}
