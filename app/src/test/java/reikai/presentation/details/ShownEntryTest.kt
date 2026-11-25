package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga

/** What a merged entry's header shows, over each type's own overlay and id check. */
class ShownEntryTest {

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a chip shows the chip's synopsis and tags under the anchor's custom title`(kind: Kind) {
        val anchor = Row(1L, description = "anchor", genre = listOf("Action"))
        val chip = Row(2L, description = "chip", genre = listOf("Romance"))

        val shown = kind.shown(anchor, chip, Info(1L, title = "Custom"), null)

        shown.title shouldBe "Custom"
        shown.description shouldBe "chip"
        shown.genre shouldBe listOf("Romance")
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a chip keeps its own cover under the anchor's custom cover url`(kind: Kind) {
        val shown = kind.shown(Row(1L, thumbnailUrl = "anchor"), Row(2L, thumbnailUrl = "chip"), CUSTOM_COVER, null)

        shown.thumbnailUrl shouldBe "chip"
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `with no chip selected the anchor shows its custom cover url`(kind: Kind) {
        kind.shown(Row(1L, thumbnailUrl = "anchor"), null, CUSTOM_COVER, null).thumbnailUrl shouldBe "custom"
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a chip's own custom title does not replace the anchor's`(kind: Kind) {
        val own = Info(2L, title = "Chip custom", thumbnailUrl = "chip-custom")

        kind.shown(Row(1L, title = "anchor"), Row(2L, title = "chip"), Info(1L, title = "Custom"), own).title shouldBe
            "Custom"
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a chip ignores a custom row read for another entry`(kind: Kind) {
        val stale = Info(3L, thumbnailUrl = "other")

        kind.shown(Row(1L), Row(2L, thumbnailUrl = "chip"), null, stale).thumbnailUrl shouldBe "chip"
    }

    /** The fields of an entry the header reads, either type's. */
    data class Row(
        val id: Long,
        val title: String = "",
        val description: String? = null,
        val genre: List<String>? = null,
        val thumbnailUrl: String? = null,
    )

    data class Info(val id: Long, val title: String? = null, val thumbnailUrl: String? = null)

    enum class Kind(val shown: (anchor: Row, chip: Row?, anchorInfo: Info?, chipInfo: Info?) -> Row) {
        MANGA({ anchor, chip, anchorInfo, chipInfo ->
            shownManga(anchor.manga(), chip?.manga(), anchorInfo?.manga(), chipInfo?.manga()).let {
                Row(it.id, it.title, it.description, it.genre, it.thumbnailUrl)
            }
        }),
        NOVEL({ anchor, chip, anchorInfo, chipInfo ->
            shownNovel(anchor.novel(), chip?.novel(), anchorInfo?.novel(), chipInfo?.novel()).let {
                Row(it.id, it.title, it.description, it.genre, it.thumbnailUrl)
            }
        }),
    }

    private companion object {
        val CUSTOM_COVER = Info(1L, thumbnailUrl = "custom")

        fun Row.manga() = Manga.create()
            .copy(id = id, title = title, description = description, genre = genre, thumbnailUrl = thumbnailUrl)

        fun Row.novel() = Novel.create()
            .copy(id = id, title = title, description = description, genre = genre, thumbnailUrl = thumbnailUrl)

        fun Info.manga() = CustomMangaInfo(mangaId = id, title = title, thumbnailUrl = thumbnailUrl)

        fun Info.novel() = CustomNovelInfo(novelId = id, title = title, thumbnailUrl = thumbnailUrl)
    }
}
