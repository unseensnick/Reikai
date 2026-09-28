package eu.kanade.tachiyomi.data.track.shikimori

import io.kotest.matchers.shouldBe
import mihon.graphql.shikimori.ShikimoriSearchMangaQuery
import mihon.graphql.shikimori.fragment.MangaFragment
import org.junit.jupiter.api.Test

/**
 * Shikimori names a one-person team's role "Story & Art", so a search result credits that person as both
 * author and artist rather than as neither.
 */
class ShikimoriCreditsTest {

    @Test
    fun `a Story & Art credit makes the person an author`() {
        result(roles = listOf("Story & Art")).authors shouldBe listOf("Mangaka")
    }

    @Test
    fun `a Story & Art credit makes the person an artist`() {
        result(roles = listOf("Story & Art")).artists shouldBe listOf("Mangaka")
    }

    @Test
    fun `an unrelated credit makes the person neither`() {
        result(roles = listOf("Original Creator")).authors shouldBe emptyList()
    }

    private fun result(roles: List<String>) = ShikimoriSearchMangaQuery.Manga(
        __typename = "Manga",
        mangaFragment = MangaFragment(
            id = "1",
            name = "Title",
            chapters = 10,
            kind = null,
            poster = null,
            score = null,
            url = "https://shikimori.one/mangas/1",
            status = null,
            airedOn = null,
            description = null,
            personRoles = listOf(MangaFragment.PersonRole(MangaFragment.Person("Mangaka"), roles)),
        ),
    ).toTrackSearch(trackId = 1L)
}
