package exh.md

import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.md.utils.FollowStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track

/** What Push favorites sends for a library entry, decided on the account's follow rather than the local row. */
class MangaDexFollowPushTest {

    private val fresh = Track(
        id = 0L,
        mangaId = 1L,
        trackerId = TrackerManager.MDLIST,
        remoteId = 0L,
        libraryId = null,
        title = "A Series",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = FollowStatus.UNFOLLOWED.long,
        score = 0.0,
        remoteUrl = "https://mangadex.org/title/abc",
        startDate = null,
        finishDate = null,
        private = false,
    )

    @Test
    fun `an entry already followed on the account is not pushed`() {
        followToPush(fresh, FollowStatus.COMPLETED.long, 8.0) shouldBe null
    }

    @Test
    fun `a pushed follow keeps the account's rating`() {
        followToPush(fresh, FollowStatus.UNFOLLOWED.long, 8.0)?.score shouldBe 8.0
    }

    @Test
    fun `an entry with no follow of its own is followed as reading`() {
        followToPush(fresh, FollowStatus.UNFOLLOWED.long, 0.0)?.status shouldBe FollowStatus.READING.long
    }

    @Test
    fun `a bound row keeps its own status when re-followed`() {
        val bound = fresh.copy(status = FollowStatus.COMPLETED.long, score = 7.0)

        followToPush(bound, FollowStatus.UNFOLLOWED.long, 0.0)?.status shouldBe FollowStatus.COMPLETED.long
    }
}
