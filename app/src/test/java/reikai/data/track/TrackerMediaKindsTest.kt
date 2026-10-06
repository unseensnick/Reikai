package reikai.data.track

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Each tracker's whole type vocabulary sorted into manga and light novel: MyAnimeList's API and Jikan's
 * mirror of it, MangaUpdates' series types, AniList's formats.
 */
class TrackerMediaKindsTest {

    @ParameterizedTest(name = "MyAnimeList {0}")
    @MethodSource("myAnimeListTypes")
    fun `a MyAnimeList type is a novel only when it names one`(type: String?, novel: Boolean) {
        isMyAnimeListNovel(type) shouldBe novel
    }

    @ParameterizedTest(name = "MangaUpdates {0}")
    @MethodSource("mangaUpdatesTypes")
    fun `a MangaUpdates type is a novel only when it is Novel`(type: String?, novel: Boolean) {
        isMangaUpdatesNovel(type) shouldBe novel
    }

    @ParameterizedTest(name = "MangaUpdates {0}")
    @MethodSource("mangaUpdatesMangaTypes")
    fun `a MangaUpdates type is manga unless it is a novel or a drama CD`(type: String?, manga: Boolean) {
        isMangaUpdatesManga(type) shouldBe manga
    }

    @ParameterizedTest(name = "AniList {0}")
    @MethodSource("anilistFormats")
    fun `an AniList format is a novel only when it is NOVEL`(format: String?, novel: Boolean) {
        isAnilistNovel(format) shouldBe novel
    }

    companion object {
        private fun kinds(matching: List<String?>, others: List<String?>): List<Arguments> =
            matching.map { Arguments.of(it, true) } + others.map { Arguments.of(it, false) }

        private val mangaUpdatesComics = listOf(
            "Manga", "Manhwa", "Manhua", "Doujinshi", "Artbook", "OEL", "Filipino", "Indonesian", "Thai",
            "Vietnamese", "Malaysian", "Nordic", "French", "Spanish", null,
        )

        @JvmStatic
        fun myAnimeListTypes() = kinds(
            matching = listOf("light_novel", "novel", "Light Novel", "Novel"),
            others = listOf(
                "manga", "one_shot", "doujinshi", "manhwa", "manhua", "oel", "unknown",
                "Manga", "One-shot", "Doujinshi", "Manhwa", "Manhua", "OEL", null,
            ),
        )

        @JvmStatic
        fun mangaUpdatesTypes() = kinds(
            matching = listOf("Novel", "novel"),
            others = mangaUpdatesComics + "Drama CD",
        )

        @JvmStatic
        fun mangaUpdatesMangaTypes() = kinds(
            matching = mangaUpdatesComics,
            others = listOf("Novel", "novel", "Drama CD", "drama cd"),
        )

        @JvmStatic
        fun anilistFormats() = kinds(
            matching = listOf("NOVEL"),
            others = listOf("MANGA", "ONE_SHOT", null),
        )
    }
}
