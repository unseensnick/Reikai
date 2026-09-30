package reikai.domain.track

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** What the details mark-read toast may claim after a push, the same for both content types. */
class ChapterPushOutcomeTest {

    private val anilist = tracker("AniList")
    private val kitsu = tracker("Kitsu")

    @Test
    fun `a push no tracker took does not say updated`() {
        ChapterPushOutcome(updated = emptyList(), failed = listOf(anilist to RATE_LIMITED)).lines() shouldBe
            listOf("AniList failed")
    }

    @Test
    fun `a push a tracker took says updated`() {
        ChapterPushOutcome(updated = listOf(anilist), failed = emptyList()).lines() shouldBe listOf("updated")
    }

    @Test
    fun `a tracker that failed beside one that took the push is named`() {
        ChapterPushOutcome(updated = listOf(kitsu), failed = listOf(anilist to RATE_LIMITED)).lines() shouldBe
            listOf("updated", "AniList failed")
    }

    @Test
    fun `a tracker whose push threw counts as failed, not updated`() {
        ChapterPushOutcome.of(
            listOf(anilist to Result.failure<Unit>(RATE_LIMITED), kitsu to Result.success(Unit)),
        ) shouldBe
            ChapterPushOutcome(updated = listOf(kitsu), failed = listOf(anilist to RATE_LIMITED))
    }

    private fun ChapterPushOutcome.lines() =
        report(updatedLine = { "updated" }, failedLine = { tracker, _ -> "${tracker.name} failed" })

    private fun tracker(name: String) = mockk<Tracker> { every { this@mockk.name } returns name }

    private companion object {
        val RATE_LIMITED = HttpException(429)
    }
}
