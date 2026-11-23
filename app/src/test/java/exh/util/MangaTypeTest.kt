package exh.util

import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.domain.manga.model.Manga

/**
 * Auto webtoon mode reads two signals: an entry's genre tags and its source's display name. Tags win
 * over the source name, and the manga / comic branches deliberately suppress a long-strip guess.
 * These cover the resulting precedence, since it is what decides whether a series opens in the
 * user's default mode or gets overridden.
 */
class MangaTypeTest {

    private val webtoon = ReadingMode.WEBTOON.flagValue

    // Genres are `|`-separated; an empty cell is no genre list, an empty source is an uninstalled one.
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "a webtoon tag reads as long strip, Webtoon, , true",
        "a long strip tag reads as long strip, Long Strip, , true",
        "a manhwa tag reads as long strip, Manhwa, , true",
        "a manhua tag reads as long strip, Manhua, , true",
        "a cyrillic manhwa tag reads as long strip, манхва, , true",
        "a cyrillic manhua tag reads as long strip, маньхуа, , true",
        "tag matching ignores case, MANHWA, , true",
        "a source name alone reads as long strip when there are no tags, , Toonily, true",
        "an ordinary manga keeps the global default, Action|Romance, SomeSource, false",
        "a manga tag suppresses a long strip source, Manga, Toonily, false",
        "a comic tag suppresses a long strip source, Adult Comic, Manhwa18, false",
        "a manga tag beats a long strip tag on the same entry, Manga|Long Strip, , false",
        "no genre and no source name keeps the global default, , , false",
        "an uninstalled extension keeps the global default, Action, , false",
    )
    fun `an entry reads as long strip by its tags, then its source name`(
        name: String,
        genres: String?,
        sourceName: String?,
        isLongStrip: Boolean,
    ) {
        defaultReaderType(manga(genres).mangaType(sourceName)) shouldBe webtoon.takeIf { isLongStrip }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groups")
    fun `any merged member reading as long strip decides the group`(
        name: String,
        members: List<Manga>,
        sourceNameOf: (Manga) -> String?,
        isLongStrip: Boolean,
    ) {
        defaultReaderType(members, sourceNameOf) shouldBe webtoon.takeIf { isLongStrip }
    }

    companion object {
        private fun manga(genres: String?, id: Long = -1L) = Manga.create().copy(
            id = id,
            genre = genres?.split('|'),
        )

        private val noSource: (Manga) -> String? = { null }

        @JvmStatic
        fun groups() = listOf(
            Arguments.of(
                "one merged member tagging long strip decides the group",
                listOf(manga("Action"), manga("Webtoons"), manga("Action")),
                noSource,
                true,
            ),
            Arguments.of(
                "a group nobody tags keeps the global default",
                listOf(manga("Action"), manga("Romance")),
                noSource,
                false,
            ),
            Arguments.of("an unmerged entry is just a group of one", listOf(manga("Manhua")), noSource, true),
            Arguments.of(
                "a manga tag only suppresses its own member, not the group",
                listOf(manga("Manga"), manga("Long Strip")),
                noSource,
                true,
            ),
            Arguments.of(
                "a member's own source name votes even when its tags are bare",
                listOf(manga("Action"), manga("Action", id = 2L)),
                { m: Manga -> "Toonily".takeIf { m.id == 2L } },
                true,
            ),
            Arguments.of("an empty group keeps the global default", emptyList<Manga>(), noSource, false),
        )
    }
}
