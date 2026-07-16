package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private const val ANILIST = 1L
private const val MAL = 2L

class RecommendationHideFilterTest {

    private fun candidate(title: String, trackerId: Long? = null, remoteId: Long? = null) =
        RelatedMangaCandidate(
            sourceId = 99L,
            trackerName = null,
            manga = SManga.create().apply {
                this.url = title
                this.title = title
            },
            trackerId = trackerId,
            remoteId = remoteId,
        )

    private fun index(
        pairs: Set<Pair<Long, Long>> = emptySet(),
        anilistIds: Set<Long> = emptySet(),
        malIds: Set<Long> = emptySet(),
        titles: Set<String> = emptySet(),
    ) = RecommendationHideFilter.Index(pairs, anilistIds, malIds, titles)

    private fun filter(inLibrary: RecommendationHideFilter.Index, status: RecommendationHideFilter.Index = index()) =
        RecommendationHideFilter(inLibrary, status, anilistTrackerId = ANILIST, malTrackerId = MAL)

    @Test
    fun `exact tracker id match hides the candidate`() {
        val f = filter(index(pairs = setOf(ANILIST to 100L)))
        f.shouldHide(candidate("Whatever", trackerId = ANILIST, remoteId = 100L)) shouldBe true
    }

    @Test
    fun `cross-tracker anilist id hides an anilist candidate tracked elsewhere`() {
        // The user tracks it on MAL, which recorded the AniList cross-ref; the candidate is AniList-origin.
        val f = filter(index(anilistIds = setOf(100L)))
        f.shouldHide(candidate("Whatever", trackerId = ANILIST, remoteId = 100L)) shouldBe true
    }

    @Test
    fun `a source-native candidate is hidden by normalized title`() {
        val f = filter(index(titles = setOf(TitleNormalizer.normalize("The Villainess Turns the Hourglass"))))
        f.shouldHide(candidate("The Villainess Turns the Hourglass!")) shouldBe true
    }

    @Test
    fun `a tracker candidate whose id is unknown still hides by title`() {
        val f = filter(index(titles = setOf(TitleNormalizer.normalize("Solo Leveling"))))
        f.shouldHide(candidate("Solo Leveling", trackerId = ANILIST, remoteId = 777L)) shouldBe true
    }

    @Test
    fun `an unrelated candidate is not hidden`() {
        val f = filter(index(pairs = setOf(ANILIST to 100L), titles = setOf(TitleNormalizer.normalize("Other"))))
        f.shouldHide(candidate("Unrelated", trackerId = ANILIST, remoteId = 200L)) shouldBe false
    }

    @Test
    fun `the status index hides independently of the in-library index`() {
        val f = filter(inLibrary = index(), status = index(pairs = setOf(ANILIST to 100L)))
        f.shouldHide(candidate("Completed", trackerId = ANILIST, remoteId = 100L)) shouldBe true
    }

    @Test
    fun `an empty filter is a no-op`() {
        filter(index()).isNoOp shouldBe true
    }
}
