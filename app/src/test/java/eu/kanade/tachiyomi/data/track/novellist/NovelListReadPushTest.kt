package eu.kanade.tachiyomi.data.track.novellist

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * A read push reaching the catalogue total completes the novel, as AniList's push and the edit sheet do;
 * without it, finishing a novel left NovelList on Reading while every other tracker said Completed.
 */
class NovelListReadPushTest {

    private val tracker = NovelList(101L)

    private fun track(read: Double, total: Long) = DbTrack.create(101L).apply {
        status = NovelList.READING
        last_chapter_read = read
        total_chapters = total
    }

    @Test
    fun `reading the last chapter completes the novel`() {
        val track = track(read = 40.0, total = 40L)

        tracker.applyReadPush(track)

        track.status shouldBe NovelList.COMPLETED
    }

    @Test
    fun `reading short of the total keeps the novel on Reading`() {
        val track = track(read = 39.0, total = 40L)

        tracker.applyReadPush(track)

        track.status shouldBe NovelList.READING
    }
}
