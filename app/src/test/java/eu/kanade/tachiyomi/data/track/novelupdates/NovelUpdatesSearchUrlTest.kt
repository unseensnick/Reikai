package eu.kanade.tachiyomi.data.track.novelupdates

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Spaces stay '+', as the site has always been sent; only the characters that break a query escape. */
class NovelUpdatesSearchUrlTest {

    @ParameterizedTest
    @CsvSource(
        "Spice & Wolf, Spice+%26+Wolf",
        "Level #1 Hero, Level+%231+Hero",
        "Kiss+Sis, Kiss%2BSis",
    )
    fun `the search text reaches the site whole`(title: String, encoded: String) {
        NovelUpdatesApi.searchUrl(title).toString() shouldBe
            "${NovelUpdatesApi.BASE_URL}/series-finder/?sf=1&sh=$encoded&sort=sdate&order=desc"
    }

    @Test
    fun `a hash in the title does not cut off the sort order`() {
        NovelUpdatesApi.searchUrl("Level #1 Hero").queryParameter("sort") shouldBe "sdate"
    }
}
