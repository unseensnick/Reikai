package reikai.data.local

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.system.ImageUtil

/** How picked files name the series they become. */
class LocalImportSeriesTest {

    @ParameterizedTest
    @CsvSource(
        "Bleach 001, Bleach",
        "Black Magic 01, Black Magic",
        "Psychic 3, Psychic",
        "Tokyo Rev 3, Tokyo Rev",
        "Saga v01, Saga",
        "Saga Vol. 2, Saga",
        "One Piece c1045, One Piece",
        "Foo ch.12, Foo",
        "Foo #3, Foo",
        "86, 86",
    )
    fun `a comic's series is its name without the trailing number`(stem: String, series: String) {
        LocalImport.seriesFromStem(stem) shouldBe series
    }

    @Test
    fun `a lone EPUB volume is named after its series, as it would be beside its siblings`() {
        val volume = LocalImport.ReadFile(
            file = ImportFile(mockk(), "foo-4.epub", 1L),
            type = ContentType.NOVELS,
            series = "Foo",
            seriesIndex = 4.0,
            author = null,
            cover = null,
            isEpub = true,
        )

        LocalImport.groupSeries(listOf(volume)).single().title shouldBe "Foo"
    }

    @ParameterizedTest
    @CsvSource("p.jpeg", "P.JPG", "p.heif", "p.png")
    fun `a page is recognised by its name alone`(name: String) {
        ImageUtil.isImage(name) shouldBe true
    }
}
