package reikai.domain.entry

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.recents.RecentlyAddedManga
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover

/**
 * The display overlay, pinned once over both content types' override rows: the novel library draws a
 * novel as a manga-shaped row, so [Manga.withCustomInfo] is fed both.
 */
class EntryCustomInfoOverlayTest {

    @Test
    fun `a null overlay passes the source through unchanged`() {
        source.withCustomInfo(null) shouldBe source
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("infoTypes")
    fun `an all-null overlay passes every field through`(info: InfoType) {
        source.withCustomInfo(info.create()) shouldBe source
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("infoTypes")
    fun `an unset field keeps the source value even when another is overridden`(info: InfoType) {
        source.withCustomInfo(info.create(title = "My Title")).author shouldBe "Source Author"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("infoTypes")
    fun `every field can be overridden at once`(info: InfoType) {
        source.withCustomInfo(info.create(title = "T", thumbnailUrl = "u", allOthers = true)) shouldBe
            source.copy(
                title = "T",
                author = "Au",
                artist = "Ar",
                description = "D",
                genre = listOf("G"),
                status = 2L,
                thumbnailUrl = "u",
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("infoTypes")
    fun `a feed row with no override of its own passes through`(info: InfoType) {
        val rows = listOf(row(id = 1))

        rows.overlayCustomInfo(mapOf(2L to info.create(title = "x")), { it.mangaId }, { withCustomInfo(it) }) shouldBe
            rows
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("infoTypes")
    fun `a feed row takes its set title`(info: InfoType) {
        val overlaid = listOf(row(id = 1)).overlayCustomInfo(
            mapOf(1L to info.create(title = "custom")),
            { it.mangaId },
            { withCustomInfo(it) },
        )

        overlaid.single().title shouldBe "custom"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("infoTypes")
    fun `a feed row keeps its own title when only the cover is overridden`(info: InfoType) {
        val overlaid = listOf(row(id = 1)).overlayCustomInfo(
            mapOf(1L to info.create(thumbnailUrl = "cover")),
            { it.mangaId },
            { withCustomInfo(it) },
        )

        overlaid.single().title shouldBe "stored"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("infoTypes")
    fun `a feed row takes its set cover`(info: InfoType) {
        val overlaid = listOf(row(id = 1)).overlayCustomInfo(
            mapOf(1L to info.create(thumbnailUrl = "cover")),
            { it.mangaId },
            { withCustomInfo(it) },
        )

        overlaid.single().coverData.url shouldBe "cover"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("infoTypes")
    fun `no overrides at all hands the feed back untouched`(info: InfoType) {
        val rows = listOf(row(id = 1))

        rows.overlayCustomInfo(emptyMap(), { it.mangaId }, { error("no override to apply") }) shouldBeSameInstanceAs
            rows
    }

    /** One content type's override row, built from the fields a case sets. */
    enum class InfoType {
        MANGA {
            override fun create(title: String?, thumbnailUrl: String?, allOthers: Boolean) = CustomMangaInfo(
                mangaId = 1L,
                title = title,
                author = "Au".takeIf { allOthers },
                artist = "Ar".takeIf { allOthers },
                description = "D".takeIf { allOthers },
                genre = listOf("G").takeIf { allOthers },
                status = 2L.takeIf { allOthers },
                thumbnailUrl = thumbnailUrl,
            )
        },
        NOVEL {
            override fun create(title: String?, thumbnailUrl: String?, allOthers: Boolean) = CustomNovelInfo(
                novelId = 1L,
                title = title,
                author = "Au".takeIf { allOthers },
                artist = "Ar".takeIf { allOthers },
                description = "D".takeIf { allOthers },
                genre = listOf("G").takeIf { allOthers },
                status = 2L.takeIf { allOthers },
                thumbnailUrl = thumbnailUrl,
            )
        },
        ;

        abstract fun create(
            title: String? = null,
            thumbnailUrl: String? = null,
            allOthers: Boolean = false,
        ): EntryCustomInfo
    }

    private val source = Manga.create().copy(
        id = 1L,
        title = "Source Title",
        author = "Source Author",
        artist = "Source Artist",
        description = "Source description",
        genre = listOf("Action", "Drama"),
        status = 1L,
        thumbnailUrl = "https://example.com/source.jpg",
    )

    private fun row(id: Long) = RecentlyAddedManga(
        mangaId = id,
        title = "stored",
        dateAdded = 0L,
        coverData = MangaCover(mangaId = id, sourceId = 1L, isMangaFavorite = true, url = "stored", lastModified = 0L),
    )

    companion object {
        @JvmStatic
        fun infoTypes() = InfoType.entries
    }
}
