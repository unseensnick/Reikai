package reikai.domain.track

import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.library.ContentType

class TrackerShownInTest {

    private fun tracker(manga: Boolean, novels: Boolean) = mockk<Tracker> {
        every { supportsManga } returns manga
        every { supportsNovels } returns novels
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "a novel-only tracker leaves with novels, false, true, MANGA, false",
        "a manga-only tracker leaves with manga, true, false, NOVELS, false",
        "a tracker holding both types stays under manga, true, true, MANGA, true",
        "a tracker holding both types stays under novels, true, true, NOVELS, true",
        "a tracker holding both types stays under all, true, true, ALL, true",
        "a novel-only tracker stays while novels show, false, true, NOVELS, true",
        "a manga-only tracker stays under all, true, false, ALL, true",
    )
    fun `a tracker is shown while one of its types shows`(
        name: String,
        manga: Boolean,
        novels: Boolean,
        shown: ContentType,
        expected: Boolean,
    ) {
        tracker(manga, novels).shownIn(shown) shouldBe expected
    }
}
