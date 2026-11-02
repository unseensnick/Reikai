package reikai.domain.track

import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

class TrackerShownInTest {

    private fun tracker(manga: Boolean, novels: Boolean) = mockk<Tracker> {
        every { supportsManga } returns manga
        every { supportsNovels } returns novels
    }

    @Test
    fun `a novel-only tracker leaves with novels`() {
        tracker(manga = false, novels = true).shownIn(ContentType.MANGA) shouldBe false
    }

    @Test
    fun `a manga-only tracker leaves with manga`() {
        tracker(manga = true, novels = false).shownIn(ContentType.NOVELS) shouldBe false
    }

    @Test
    fun `a tracker holding both types stays under either`() {
        ContentType.entries.map { tracker(manga = true, novels = true).shownIn(it) } shouldBe
            listOf(true, true, true)
    }

    @Test
    fun `a tracker stays while its one type shows`() {
        listOf(
            tracker(manga = false, novels = true).shownIn(ContentType.NOVELS),
            tracker(manga = true, novels = false).shownIn(ContentType.ALL),
        ) shouldBe listOf(true, true)
    }
}
