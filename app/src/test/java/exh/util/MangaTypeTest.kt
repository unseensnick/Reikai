package exh.util

import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

/**
 * Auto webtoon mode reads two signals: an entry's genre tags and its source's display name. Tags win
 * over the source name, and the manga / comic branches deliberately suppress a long-strip guess.
 * These cover the resulting precedence, since it is what decides whether a series opens in the
 * user's default mode or gets overridden.
 */
class MangaTypeTest {

    private fun manga(vararg genre: String) = Manga.create().copy(
        genre = genre.toList().takeIf { it.isNotEmpty() },
    )

    private val webtoon = ReadingMode.WEBTOON.flagValue

    @Test
    fun `a webtoon tag reads as long strip`() {
        defaultReaderType(manga("Webtoon").mangaType(null)) shouldBe webtoon
    }

    @Test
    fun `a long strip tag reads as long strip`() {
        defaultReaderType(manga("Long Strip").mangaType(null)) shouldBe webtoon
    }

    @Test
    fun `a manhwa tag reads as long strip`() {
        defaultReaderType(manga("Manhwa").mangaType(null)) shouldBe webtoon
    }

    @Test
    fun `a manhua tag reads as long strip`() {
        defaultReaderType(manga("Manhua").mangaType(null)) shouldBe webtoon
    }

    @Test
    fun `cyrillic long strip tags read as long strip`() {
        defaultReaderType(manga("манхва").mangaType(null)) shouldBe webtoon
        defaultReaderType(manga("маньхуа").mangaType(null)) shouldBe webtoon
    }

    @Test
    fun `a source name alone reads as long strip when there are no tags`() {
        defaultReaderType(manga().mangaType("Toonily")) shouldBe webtoon
    }

    @Test
    fun `an ordinary manga keeps the global default`() {
        defaultReaderType(manga("Action", "Romance").mangaType("SomeSource")) shouldBe null
    }

    @Test
    fun `a manga tag suppresses a long strip source`() {
        defaultReaderType(manga("Manga").mangaType("Toonily")) shouldBe null
    }

    @Test
    fun `a comic tag suppresses a long strip source`() {
        defaultReaderType(manga("Adult Comic").mangaType("Manhwa18")) shouldBe null
    }

    @Test
    fun `a manga tag beats a long strip tag on the same entry`() {
        defaultReaderType(manga("Manga", "Long Strip").mangaType(null)) shouldBe null
    }

    @Test
    fun `no genre and no source name keeps the global default`() {
        defaultReaderType(manga().mangaType(null)) shouldBe null
    }

    @Test
    fun `an uninstalled extension leaves the source name null and keeps the global default`() {
        defaultReaderType(manga("Action").mangaType(null)) shouldBe null
    }

    @Test
    fun `tag matching ignores case`() {
        defaultReaderType(manga("MANHWA").mangaType(null)) shouldBe webtoon
    }
}
