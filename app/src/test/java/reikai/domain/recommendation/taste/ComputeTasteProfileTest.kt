package reikai.domain.recommendation.taste

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ComputeTasteProfileTest {

    private val compute = ComputeTasteProfile()

    private fun entry(
        score: Double,
        status: TrackStatus,
        tags: List<String>,
        id: Long = nextId++,
    ) = TrackedEntry(trackerId = 1L, remoteId = id, title = "t$id", score = score, status = status, tags = tags)

    private var nextId = 1L

    private fun tracked(
        trackerId: Long,
        remoteId: Long,
        tags: List<String>,
        status: TrackStatus,
        score: Double,
        malId: Long? = null,
        anilistId: Long? = null,
    ) = TrackedEntry(trackerId, remoteId, "t$remoteId", score, status, tags, malId, anilistId)

    /** The MAL row comes first and carries more tags, so only the AniList-first priority keeps the AniList row. */
    @Test
    fun `a series tracked on AniList and MyAnimeList counts once, as its AniList row`() {
        val aniRow = tracked(2L, 10L, listOf("action"), TrackStatus.COMPLETED, 1.0, malId = 5L, anilistId = 10L)
        val malRow = tracked(1L, 5L, listOf("action", "drama"), TrackStatus.DROPPED, -1.0, malId = 5L)
        val single = tracked(2L, 11L, listOf("romance"), TrackStatus.COMPLETED, 1.0, malId = 6L, anilistId = 11L)

        compute(listOf(malRow, aniRow, single)) shouldBe compute(listOf(aniRow, single))
    }

    @Test
    fun `a series joined only by its AniList id counts once, as its AniList row`() {
        val aniRow = tracked(2L, 20L, listOf("horror"), TrackStatus.COMPLETED, 1.0, anilistId = 20L)
        val kitsuRow = tracked(3L, 30L, listOf("horror", "mystery"), TrackStatus.DROPPED, -1.0, anilistId = 20L)

        compute(listOf(kitsuRow, aniRow)) shouldBe compute(listOf(aniRow))
    }

    @Test
    fun `empty input yields the empty profile`() {
        compute(emptyList()) shouldBe TasteProfile.EMPTY
    }

    @Test
    fun `a completed highly-rated tag scores near the top of the range`() {
        val profile = compute(listOf(entry(1.0, TrackStatus.COMPLETED, listOf("action"))))
        profile.tagScores["action"]!! shouldBe 1.0
        profile.totalEntries shouldBe 1
    }

    @Test
    fun `a dropped tag scores negative`() {
        // Unrated (-1.0 substitutes 0.5) so the negative DROPPED weight makes it -0.5.
        val profile = compute(listOf(entry(-1.0, TrackStatus.DROPPED, listOf("isekai"))))
        profile.tagScores["isekai"]!! shouldBeLessThan 0.0
    }

    @Test
    fun `an unrated entry still contributes via its status weight`() {
        // Unrated (-1.0) substitutes 0.5, so a completed unrated entry is mildly positive, not zero.
        val profile = compute(listOf(entry(-1.0, TrackStatus.COMPLETED, listOf("comedy"))))
        profile.tagScores["comedy"]!! shouldBe 0.5
    }

    @Test
    fun `plan-to-read counts toward exposure but not toward the score`() {
        val profile = compute(
            listOf(
                entry(1.0, TrackStatus.COMPLETED, listOf("drama")),
                entry(0.9, TrackStatus.PLAN_TO_READ, listOf("drama")),
            ),
        )
        // Exposure counts both entries; the score reflects only the completed one (weight 0 skips PTR).
        profile.tagEntryCounts["drama"] shouldBe 2
        profile.tagScores["drama"]!! shouldBe 1.0
    }

    @Test
    fun `a tag with mixed completed and dropped does not divide by zero`() {
        val profile = compute(
            listOf(
                entry(1.0, TrackStatus.COMPLETED, listOf("mecha")),
                entry(0.0, TrackStatus.DROPPED, listOf("mecha")),
            ),
        )
        // Denominator uses |weight| (1.0 + 1.0), so the result is finite and net positive here.
        profile.tagScores["mecha"]!! shouldBeGreaterThan 0.0
    }
}
