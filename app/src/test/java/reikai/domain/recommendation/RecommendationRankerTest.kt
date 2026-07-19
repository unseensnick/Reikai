package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.recommendation.taste.TasteProfile

class RecommendationRankerTest {

    private fun candidate(url: String, title: String): RelatedMangaCandidate {
        val manga = SManga.create().apply {
            this.url = url
            this.title = title
        }
        return RelatedMangaCandidate(
            sourceId = 1L,
            trackerName = null,
            manga = manga,
            origin = RecommendationOrigin.SourceNative("test"),
        )
    }

    @Test
    fun `floats a title several sources agree on above single-source titles`() {
        val ranker = RecommendationRanker()
        // Pool is in popularity order; the agreed-on title is last.
        val pool = listOf(candidate("/a", "A"), candidate("/c", "C"), candidate("/b", "B"))
        val agreement = mapOf("/a" to 1, "/c" to 1, "/b" to 3)

        val ranked = ranker.rank(pool, TasteProfile.EMPTY, agreement)

        ranked.first().manga.url shouldBe "/b"
    }

    @Test
    fun `keeps popularity order when no title is agreed on`() {
        val ranker = RecommendationRanker()
        val pool = listOf(candidate("/a", "A"), candidate("/b", "B"))
        val agreement = mapOf("/a" to 1, "/b" to 1)

        val ranked = ranker.rank(pool, TasteProfile.EMPTY, agreement)

        ranked.map { it.manga.url } shouldBe listOf("/a", "/b")
    }

    @Test
    fun `a zero agreement count does not corrupt the taste-scored ranking`() {
        // With a real taste profile the scoring path runs ln(agreement); a 0 count yields -Infinity,
        // not NaN, so the sort stays well-ordered and keeps every candidate instead of dropping one.
        val ranker = RecommendationRanker()
        val taste =
            TasteProfile(tagScores = mapOf("action" to 0.5), tagEntryCounts = mapOf("action" to 1), totalEntries = 1)
        val pool = listOf(candidate("/a", "A"), candidate("/b", "B"))
        val agreement = mapOf("/a" to 1, "/b" to 0)

        val ranked = ranker.rank(pool, taste, agreement)

        ranked.map { it.manga.url } shouldContainExactlyInAnyOrder listOf("/a", "/b")
    }
}
