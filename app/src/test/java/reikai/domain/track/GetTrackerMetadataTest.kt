package reikai.domain.track

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.track.model.Track

/**
 * "Fill from tracker" writes its genres into the entry's custom info, which the library's own lewd
 * filter reads back, so an unscreened autofill reclassifies an entry rather than just labelling it.
 */
class GetTrackerMetadataTest {

    private val track = mockk<Track>()

    private fun getTrackerMetadata(
        showAdult: Boolean,
        genres: List<String>?,
        alwaysAdult: Set<String> = emptySet(),
        neverAdult: Set<String> = emptySet(),
    ): Pair<GetTrackerMetadata, Tracker> {
        val tracker = mockk<Tracker>()
        coEvery { tracker.getMangaMetadata(track) } returns
            TrackMangaMetadata(title = "Title", genres = genres)
        // Stubbed explicitly: a relaxed mock returns a bare Object for a generic Preference<T>.
        val show = mockk<Preference<Boolean>>()
        every { show.get() } returns showAdult
        val always = mockk<Preference<Set<String>>>()
        every { always.get() } returns alwaysAdult
        val never = mockk<Preference<Set<String>>>()
        every { never.get() } returns neverAdult
        val preferences = mockk<TrackPreferences>()
        every { preferences.showAdultTrackerContent } returns show
        every { preferences.alwaysAdultTags } returns always
        every { preferences.neverAdultTags } returns never
        return GetTrackerMetadata(preferences) to tracker
    }

    @Test
    fun `an explicit genre is dropped and the rest are kept`() = runTest {
        val (interactor, tracker) = getTrackerMetadata(false, listOf("Action", "Hentai", "Drama"))
        interactor.await(track, tracker).genres shouldBe listOf("Action", "Drama")
    }

    @Test
    fun `every genre survives once the user has opted in`() = runTest {
        val (interactor, tracker) = getTrackerMetadata(true, listOf("Action", "Hentai"))
        interactor.await(track, tracker).genres shouldBe listOf("Action", "Hentai")
    }

    @Test
    fun `a list of nothing but explicit genres becomes null rather than empty`() = runTest {
        val (interactor, tracker) = getTrackerMetadata(false, listOf("Hentai", "Erotica"))
        interactor.await(track, tracker).genres shouldBe null
    }

    @Test
    fun `a tracker that returns no genres is left alone`() = runTest {
        val (interactor, tracker) = getTrackerMetadata(false, null)
        interactor.await(track, tracker).genres shouldBe null
    }

    @Test
    fun `the user's denied tags are dropped here too`() = runTest {
        val (interactor, tracker) =
            getTrackerMetadata(false, listOf("Action", "Ecchi"), alwaysAdult = setOf("ecchi"))
        interactor.await(track, tracker).genres shouldBe listOf("Action")
    }

    @Test
    fun `the user's allowed tags survive here too`() = runTest {
        val (interactor, tracker) =
            getTrackerMetadata(false, listOf("Erotica"), neverAdult = setOf("erotica"))
        interactor.await(track, tracker).genres shouldBe listOf("Erotica")
    }

    @Test
    fun `the other fields are never touched`() = runTest {
        val (interactor, tracker) = getTrackerMetadata(false, listOf("Hentai"))
        interactor.await(track, tracker).title shouldBe "Title"
    }
}
