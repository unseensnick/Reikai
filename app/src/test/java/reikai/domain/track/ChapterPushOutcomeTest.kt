package reikai.domain.track

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** Which trackers the details mark-read toast may name after a push, the same for both content types. */
class ChapterPushOutcomeTest {

    private val anilist = tracker("AniList")
    private val kitsu = tracker("Kitsu")

    @Test
    fun `only a tracker whose push threw counts as failed`() {
        ChapterPushOutcome.of(
            listOf(anilist to Result.failure<Unit>(RATE_LIMITED), kitsu to Result.success(Unit)),
        ) shouldBe
            ChapterPushOutcome(failed = listOf(anilist to RATE_LIMITED))
    }

    private fun tracker(name: String) = mockk<Tracker> { every { this@mockk.name } returns name }

    private companion object {
        val RATE_LIMITED = HttpException(429)
    }
}
