package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.withCustomInfo
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.withCustomInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga

class ShownEntryTest {

    @Test
    fun `a manga chip shows the chip's synopsis and tags under the anchor's custom title`() {
        val anchor = Manga.create().copy(id = 1L, description = "anchor", genre = listOf("Action"))
        val chip = Manga.create().copy(id = 2L, description = "chip", genre = listOf("Romance"))
        val overlay = CustomMangaInfo(mangaId = 1L, title = "Custom")

        val shown = shownMangaOf(anchor, chip, overlay)

        Triple(shown.title, shown.description, shown.genre) shouldBe Triple("Custom", "chip", listOf("Romance"))
    }

    @Test
    fun `a novel chip shows the chip's synopsis and tags under the anchor's custom title`() {
        val anchor = Novel.create().copy(id = 1L, description = "anchor", genre = listOf("Action"))
        val chip = Novel.create().copy(id = 2L, description = "chip", genre = listOf("Romance"))
        val overlay = CustomNovelInfo(novelId = 1L, title = "Custom")

        val shown = shownNovelOf(anchor, chip, overlay)

        Triple(shown.title, shown.description, shown.genre) shouldBe Triple("Custom", "chip", listOf("Romance"))
    }

    @Test
    fun `a manga chip keeps its own cover under the anchor's custom cover url`() {
        val anchor = Manga.create().copy(id = 1L, thumbnailUrl = "anchor")
        val chip = Manga.create().copy(id = 2L, thumbnailUrl = "chip")
        val overlay = CustomMangaInfo(mangaId = 1L, thumbnailUrl = "custom")

        shownMangaOf(anchor, chip, overlay).thumbnailUrl shouldBe "chip"
    }

    @Test
    fun `a novel chip keeps its own cover under the anchor's custom cover url`() {
        val anchor = Novel.create().copy(id = 1L, thumbnailUrl = "anchor")
        val chip = Novel.create().copy(id = 2L, thumbnailUrl = "chip")
        val overlay = CustomNovelInfo(novelId = 1L, thumbnailUrl = "custom")

        shownNovelOf(anchor, chip, overlay).thumbnailUrl shouldBe "chip"
    }

    @Test
    fun `with no chip selected the anchor shows its custom cover url`() {
        val anchor = Manga.create().copy(id = 1L, thumbnailUrl = "anchor")
        val overlay = CustomMangaInfo(mangaId = 1L, thumbnailUrl = "custom")

        shownMangaOf(anchor, null, overlay).thumbnailUrl shouldBe "custom"
    }

    // The same cover rule the two adapters pass.
    private fun shownMangaOf(anchor: Manga, chip: Manga?, overlay: CustomMangaInfo) =
        shownEntry(anchor, chip, { it.withCustomInfo(overlay) }) { entry, own ->
            entry.copy(thumbnailUrl = own.thumbnailUrl)
        }

    private fun shownNovelOf(anchor: Novel, chip: Novel?, overlay: CustomNovelInfo) =
        shownEntry(anchor, chip, { it.withCustomInfo(overlay) }) { entry, own ->
            entry.copy(thumbnailUrl = own.thumbnailUrl)
        }
}
