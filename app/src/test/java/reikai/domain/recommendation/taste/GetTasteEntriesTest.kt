package reikai.domain.recommendation.taste

import eu.kanade.domain.track.service.TrackPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

/**
 * The adult-content setting as the recommendation layer sees it. The companion rule, that the
 * anti-echo hide filter is deliberately not filtered, lives in BuildRecommendationHideFilterAdultTest.
 */
class GetTasteEntriesTest {

    private val clean = entry(remoteId = 1L, tags = listOf("action"))
    private val explicit = entry(remoteId = 2L, tags = listOf("hentai"))

    private fun entry(remoteId: Long, tags: List<String>) = TrackedEntry(
        trackerId = 1L,
        remoteId = remoteId,
        title = "Title $remoteId",
        score = -1.0,
        status = TrackStatus.READING,
        tags = tags,
    )

    private fun getTasteEntries(
        showAdult: Boolean,
        alwaysAdult: Set<String> = emptySet(),
        neverAdult: Set<String> = emptySet(),
    ): GetTasteEntries {
        val repository = mockk<TasteLibraryRepository>()
        coEvery { repository.getAll() } returns listOf(clean, explicit)
        val preference = mockk<Preference<Boolean>>()
        every { preference.get() } returns showAdult
        // Stubbed explicitly: a relaxed mock returns a bare Object for a generic Preference<T>.
        val always = mockk<Preference<Set<String>>>()
        every { always.get() } returns alwaysAdult
        val never = mockk<Preference<Set<String>>>()
        every { never.get() } returns neverAdult
        val preferences = mockk<TrackPreferences>()
        every { preferences.showAdultTrackerContent } returns preference
        every { preferences.alwaysAdultTags } returns always
        every { preferences.neverAdultTags } returns never
        return GetTasteEntries(repository, preferences)
    }

    @Test
    fun `an explicit entry is dropped when adult content is not shown`() = runTest {
        getTasteEntries(showAdult = false).await() shouldContainExactly listOf(clean)
    }

    @Test
    fun `every entry is kept when the user has opted in`() = runTest {
        getTasteEntries(showAdult = true).await() shouldContainExactly listOf(clean, explicit)
    }

    @Test
    fun `the user's allowed tags reach the filter`() = runTest {
        getTasteEntries(showAdult = false, neverAdult = setOf("hentai"))
            .await() shouldContainExactly listOf(clean, explicit)
    }

    @Test
    fun `the user's denied tags reach the filter`() = runTest {
        getTasteEntries(showAdult = false, alwaysAdult = setOf("action"))
            .await() shouldContainExactly emptyList()
    }
}
