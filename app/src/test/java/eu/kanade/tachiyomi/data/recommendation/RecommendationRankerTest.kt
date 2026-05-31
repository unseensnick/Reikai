package eu.kanade.tachiyomi.data.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCandidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import yokai.domain.library.taste.model.TasteProfile

class RecommendationRankerTest {

    private fun candidate(url: String, title: String): RelatedMangaCandidate {
        val manga = SManga.create().apply {
            this.url = url
            this.title = title
        }
        return RelatedMangaCandidate(sourceId = 1L, trackerName = null, manga = manga)
    }

    @Test
    fun `floats a title several sources agree on above single-source titles`() {
        val ranker = RecommendationRanker()
        // Pool is in popularity order; the agreed-on title is last.
        val pool = listOf(candidate("/a", "A"), candidate("/c", "C"), candidate("/b", "B"))
        val agreement = mapOf("/a" to 1, "/c" to 1, "/b" to 3)

        val ranked = ranker.rank(pool, TasteProfile.EMPTY, agreement)

        assertEquals("/b", ranked.first().manga.url)
    }

    @Test
    fun `keeps popularity order when no title is agreed on`() {
        val ranker = RecommendationRanker()
        val pool = listOf(candidate("/a", "A"), candidate("/b", "B"))
        val agreement = mapOf("/a" to 1, "/b" to 1)

        val ranked = ranker.rank(pool, TasteProfile.EMPTY, agreement)

        assertEquals(listOf("/a", "/b"), ranked.map { it.manga.url })
    }
}
