package eu.kanade.tachiyomi.data.track.novellist

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * NovelList identifies a novel by UUID while `remote_id` is a `Long`, so identity rides in the
 * tracking URL and every call reads it back from there. If that round trip breaks, every write and
 * unbind addresses the wrong path, which is the failure this pins.
 */
class NovelListIdentityTest {

    private val uuid = "019743f3-4576-7e30-92ef-5078eb186a6c"

    private fun trackAt(url: String) = DomainTrack(
        id = 1L,
        mangaId = 2L,
        trackerId = 101L,
        remoteId = surrogateIdOf(uuid),
        libraryId = null,
        title = "A novel",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 1L,
        score = 0.0,
        remoteUrl = url,
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    @Test
    fun `the uuid survives the round trip through the tracking url`() {
        trackAt(novelListTrackingUrl("mushoku-tensei-ln", uuid)).uuid shouldBe uuid
    }

    /** The site answers 404 for a uuid path, so the slug has to stay the part a browser opens. */
    @Test
    fun `the tracking url still opens the human page`() {
        novelListTrackingUrl("mushoku-tensei-ln", uuid)
            .substringBefore("#") shouldBe "https://www.novellist.co/novels/mushoku-tensei-ln"
    }

    /** A row without the fragment must read as empty, not as the whole URL addressed as an id. */
    @Test
    fun `a tracking url carrying no uuid yields nothing`() {
        trackAt("https://www.novellist.co/novels/mushoku-tensei-ln").uuid shouldBe ""
    }

    @Test
    fun `the surrogate id is positive and distinguishes different novels`() {
        val other = surrogateIdOf("019743f3-4040-75a9-a141-7e1b0c66e8c2")

        surrogateIdOf(uuid) shouldNotBe 0L
        (surrogateIdOf(uuid) > 0L) shouldBe true
        surrogateIdOf(uuid) shouldNotBe other
    }

    @Test
    fun `the surrogate id is stable across calls`() {
        surrogateIdOf(uuid) shouldBe surrogateIdOf(uuid)
    }

    @Test
    fun `only a real uuid is taken as an id search`() {
        isUuid(uuid) shouldBe true
        isUuid("mushoku-tensei-ln") shouldBe false
    }
}
