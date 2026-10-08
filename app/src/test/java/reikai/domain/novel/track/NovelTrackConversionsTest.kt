package reikai.domain.novel.track

import eu.kanade.domain.track.model.toDomainTrack
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelTrack

class NovelTrackConversionsTest {

    private fun novelTrack() = NovelTrack(
        id = 7L,
        novelId = 42L,
        trackerId = 2L,
        remoteId = 999L,
        libraryId = 3L,
        title = "A Novel",
        lastChapterRead = 12.0,
        totalChapters = 100,
        status = 1,
        score = 8.0,
        remoteUrl = "https://example/999",
        startDate = 111L,
        finishDate = 222L,
        private = false,
    )

    @Test
    fun `toDbTrack carries the novel id in the manga_id slot`() {
        val db = novelTrack().toDbTrack()
        db.manga_id shouldBe 42L
        db.tracker_id shouldBe 2L
        db.remote_id shouldBe 999L
        db.last_chapter_read shouldBe 12.0
        db.tracking_url shouldBe "https://example/999"
    }

    @Test
    fun `round-trips through DbTrack without loss`() {
        val original = novelTrack()
        original.toDbTrack().toNovelTrack() shouldBe original
    }

    @Test
    fun `an id-less track converts only where no id is required, as a manga track does`() {
        val idless = novelTrack().toDbTrack().apply { id = null }
        listOf(idless.toNovelTrack()?.id, idless.toNovelTrack(idRequired = false)?.id) shouldBe
            listOf(idless.toDomainTrack()?.id, idless.toDomainTrack(idRequired = false)?.id)
    }

    @Test
    fun `toUiTrack exposes the novel id as the domain track manga id`() {
        val ui = novelTrack().toUiTrack()
        ui.mangaId shouldBe 42L
        ui.trackerId shouldBe 2L
        ui.lastChapterRead shouldBe 12.0
    }
}
