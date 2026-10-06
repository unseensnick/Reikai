package reikai.domain.source

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A source page becomes the manga it lists once each, in the source's order, under that source. */
class ListedMangaTest {

    @Test
    fun `a url the page lists twice is listed once, in source order`() {
        page("/a", "/b", "/a").listedManga(SOURCE_ID).map { it.url } shouldBe listOf("/a", "/b")
    }

    @Test
    fun `each listed manga belongs to the source that answered`() {
        page("/a").listedManga(SOURCE_ID).single().source shouldBe SOURCE_ID
    }

    private fun page(vararg urls: String) = MangasPage(
        urls.map { url ->
            SManga.create().also {
                it.url = url
                it.title = url
            }
        },
        hasNextPage = false,
    )

    private companion object {
        const val SOURCE_ID = 7L
    }
}
