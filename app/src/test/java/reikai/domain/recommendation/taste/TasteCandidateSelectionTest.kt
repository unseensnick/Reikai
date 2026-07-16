package reikai.domain.recommendation.taste

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The two pure selection helpers behind [TasteCandidateFetcher]: tag-search picks the manga's tracker
 * genres the user likes; cross-rec seeds from the user's highly-rated titles the tracker itself lists
 * among the manga's recommendations.
 */
class TasteCandidateSelectionTest {

    private fun profile(vararg scores: Pair<String, Double>) =
        TasteProfile(tagScores = scores.toMap(), tagEntryCounts = emptyMap(), totalEntries = scores.size)

    private var nextId = 1L
    private fun entry(
        score: Double,
        status: TrackStatus,
        tags: List<String> = emptyList(),
        title: String = "t$nextId",
        remoteId: Long = nextId++,
        trackerId: Long = 1L,
    ) = TrackedEntry(
        trackerId = trackerId,
        remoteId = remoteId,
        title = title,
        score = score,
        status = status,
        tags = tags,
    )

    // region selectContextualTags

    @Test
    fun `tag search keeps only the current manga's genres that score positively, ranked by score`() {
        val profile = profile("action" to 0.9, "isekai" to -0.4, "comedy" to 0.3)
        selectContextualTags(profile, currentGenres = listOf("Comedy", "Action", "Isekai"), n = 3)
            .shouldContainExactly("action", "comedy")
    }

    @Test
    fun `tag search picks different tags for a different manga's genres`() {
        val profile = profile("action" to 0.9, "romance" to 0.8, "horror" to 0.7)
        val forActionManga = selectContextualTags(profile, listOf("action"), n = 3)
        val forRomanceManga = selectContextualTags(profile, listOf("romance"), n = 3)
        (forActionManga != forRomanceManga) shouldBe true
    }

    @Test
    fun `tag search returns nothing when none of the manga's genres are liked`() {
        val profile = profile("action" to 0.9)
        selectContextualTags(profile, currentGenres = listOf("horror"), n = 3) shouldBe emptyList()
    }

    @Test
    fun `tag search caps at n`() {
        val profile = profile("a" to 0.9, "b" to 0.8, "c" to 0.7, "d" to 0.6)
        selectContextualTags(profile, listOf("a", "b", "c", "d"), n = 2).shouldContainExactly("a", "b")
    }

    // endregion

    // region selectCrossRecSeeds

    @Test
    fun `cross-rec seeds only from favorites the tracker lists among the manga's recommendations`() {
        val linked = entry(0.9, TrackStatus.COMPLETED, remoteId = 10L, title = "linked")
        val unlinked = entry(0.95, TrackStatus.COMPLETED, remoteId = 99L, title = "unlinked")
        selectCrossRecSeeds(listOf(unlinked, linked), recsIds = setOf(10L), trackerId = 1L, max = 5)
            .map { it.title }.shouldContainExactly("linked")
    }

    @Test
    fun `cross-rec excludes low-scored and non-active entries even when linked`() {
        val belowThreshold = entry(0.5, TrackStatus.COMPLETED, remoteId = 10L)
        val dropped = entry(0.99, TrackStatus.DROPPED, remoteId = 11L)
        val planned = entry(0.99, TrackStatus.PLAN_TO_READ, remoteId = 12L)
        selectCrossRecSeeds(
            listOf(belowThreshold, dropped, planned),
            setOf(10L, 11L, 12L),
            trackerId = 1L,
            max = 5,
        ) shouldBe
            emptyList()
    }

    @Test
    fun `cross-rec excludes a linked favorite tracked on a different tracker`() {
        val otherTracker = entry(0.9, TrackStatus.COMPLETED, remoteId = 10L, trackerId = 2L, title = "other")
        selectCrossRecSeeds(listOf(otherTracker), recsIds = setOf(10L), trackerId = 1L, max = 5) shouldBe emptyList()
    }

    @Test
    fun `cross-rec orders seeds by score and caps at max`() {
        val seeds = (1..5).map { entry(0.8 + it * 0.01, TrackStatus.READING, remoteId = it.toLong(), title = "f$it") }
        val result = selectCrossRecSeeds(seeds, recsIds = (1L..5L).toSet(), trackerId = 1L, max = 2)
        result.map { it.title }.shouldContainExactly("f5", "f4")
    }

    // endregion
}
