package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.recommendation.taste.TasteProfile

class RecommendationAssemblyTest {

    private fun source(url: String, genre: String? = null) = RelatedMangaCandidate(
        sourceId = 1L,
        manga = SManga.create().apply {
            this.url = url
            title = url
            this.genre = genre
        },
        origin = RecommendationOrigin.SourceNative("source"),
    )

    private fun tracker(url: String, trackerId: Long) = RelatedMangaCandidate(
        sourceId = RECOMMENDS_SOURCE,
        manga = SManga.create().apply {
            this.url = url
            title = url
        },
        origin = RecommendationOrigin.Tracker("tracker $trackerId"),
        trackerId = trackerId,
    )

    private fun hiding(vararg titles: String) = RecommendationHideFilter(
        RecommendationHideFilter.Index(
            emptySet(),
            emptySet(),
            emptySet(),
            titles.map(TitleNormalizer::normalize).toSet(),
        ),
        RecommendationHideFilter.Index.EMPTY,
        anilistTrackerId = 100L,
        malTrackerId = 200L,
    )

    private fun assembly(hideFilter: RecommendationHideFilter = hiding(), taste: TasteProfile = TasteProfile.EMPTY) =
        RecommendationAssembly(hideFilter, RecommendationRanker(), taste)

    private fun pool(candidates: List<RelatedMangaCandidate>) = RelatedPool(candidates, emptyMap())

    @Test
    fun `a source that fills the carousel alone still leaves room for tracker recommendations`() {
        val candidates = (1..40).map { source("s$it") } + (1..5).map { tracker("t$it", trackerId = 1L) }

        val carousel = assembly().assemble(pool(candidates), cap = 30)

        carousel.count { it.sourceId == RECOMMENDS_SOURCE } shouldBe 5
    }

    @Test
    fun `tracker recommendations alternate between trackers`() {
        val candidates = (1..40).map { source("s$it") } +
            listOf(tracker("a1", 1L), tracker("a2", 1L), tracker("b1", 2L), tracker("b2", 2L))

        val carousel = assembly().assemble(pool(candidates), cap = 30)

        carousel.filter { it.sourceId == RECOMMENDS_SOURCE }.map { it.manga.url } shouldBe
            listOf("a1", "b1", "a2", "b2")
    }

    @Test
    fun `the popularity picks come from suggestions that will show`() {
        // In popularity order. Serendipity keeps the most popular fifth in place; taste reorders the rest,
        // and here it would sink s3 and s4, so they lead only if the picks are made after hiding s1 and s2.
        val candidates = (1..10).map {
            source("s$it", genre = mapOf(3 to "Horror", 4 to "Horror", 10 to "Romance")[it])
        }
        val taste = TasteProfile(
            tagScores = mapOf("horror" to -1.0, "romance" to 1.0),
            tagEntryCounts = mapOf("horror" to 1, "romance" to 1),
            totalEntries = 2,
        )

        val carousel = assembly(hideFilter = hiding("s1", "s2"), taste = taste).assemble(pool(candidates), cap = 30)

        carousel.take(2).map { it.manga.url } shouldBe listOf("s3", "s4")
    }
}
