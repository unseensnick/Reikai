package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.withCustomInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.withCustomInfo

class ShownEntryTest {

    @Test
    fun `a manga chip shows the chip's synopsis and tags under the anchor's custom title`() {
        val anchor = Manga.create().copy(id = 1L, description = "anchor", genre = listOf("Action"))
        val chip = Manga.create().copy(id = 2L, description = "chip", genre = listOf("Romance"))
        val overlay = CustomMangaInfo(mangaId = 1L, title = "Custom")

        val shown = shownEntry(anchor, chip) { it.withCustomInfo(overlay) }

        Triple(shown.title, shown.description, shown.genre) shouldBe Triple("Custom", "chip", listOf("Romance"))
    }

    @Test
    fun `a novel chip shows the chip's synopsis and tags under the anchor's custom title`() {
        val anchor = Novel.create().copy(id = 1L, description = "anchor", genre = listOf("Action"))
        val chip = Novel.create().copy(id = 2L, description = "chip", genre = listOf("Romance"))
        val overlay = CustomNovelInfo(novelId = 1L, title = "Custom")

        val shown = shownEntry(anchor, chip) { it.withCustomInfo(overlay) }

        Triple(shown.title, shown.description, shown.genre) shouldBe Triple("Custom", "chip", listOf("Romance"))
    }

    @Test
    fun `with no chip selected the anchor is shown`() {
        val anchor = Manga.create().copy(id = 1L, description = "anchor")

        shownEntry(anchor, null) { it }.description shouldBe "anchor"
    }
}
